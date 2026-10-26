package tribixbite.cleverkeys

import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * [LargeTextCommit]: clipboard text of any stored size reaches the editor as ordered chunks
 * below the binder limit, inside one batch edit, never splitting a surrogate pair; a refused
 * chunk is retried in place (Saga 2026-10-25, a 524,000-char entry failed as one transaction).
 * Chunks are bounded by their size ON THE WIRE (UTF-8 on API 34+, UTF-16 before), not by their
 * char count, and are as large as that budget allows: Chrome re-lays out the whole textarea per
 * commit, so fewer chunks paste space-separated text faster (Saga 2026-10-26, docs/eval/
 * 2026-10-26-large-paste-chunk-size.md). [wireBytes] here is an independent oracle.
 * Pure JVM apart from android.jar's interface: the connection is a recording [Proxy].
 */
class LargeTextCommitTest {

    private val calls = mutableListOf<String>()
    private val commits = mutableListOf<String>()
    /** `commitText` call numbers (1-based) the connection refuses. */
    private var refused: (Int) -> Boolean = { false }
    private var attempts = 0
    private val pauses = mutableListOf<Long>()
    private val recordPause: (Long) -> Unit = { pauses += it }

    private val conn = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
    ) { _, method, args ->
        calls += method.name
        when (method.name) {
            "commitText" -> {
                assertEquals("cursor after the text", 1, args!![1])
                val accepted = !refused(++attempts)
                if (accepted) commits += (args[0] as CharSequence).toString()
                accepted
            }
            else -> if (method.returnType == Boolean::class.javaPrimitiveType) true else null
        }
    } as InputConnection

    @Test
    fun shortTextIsOneCommitInOneBatchEdit() {
        assertTrue(LargeTextCommit.commit(conn, "hello"))
        assertEquals(listOf("beginBatchEdit", "commitText", "endBatchEdit"), calls)
        assertEquals(listOf("hello"), commits)
    }

    @Test
    fun textOfExactlyOneBudgetIsNotSplit() {
        // 96 Ki ASCII chars are 192 KiB as UTF-16 (the larger of the two wire sizes).
        val text = "x".repeat(96 * 1024)
        assertTrue(LargeTextCommit.commit(conn, text))
        assertEquals(1, commits.size)
        assertTrue(LargeTextCommit.commit(conn, text + "x"))
        assertEquals("one char over the budget is a second chunk", 3, commits.size)
    }

    @Test
    fun longTextIsSplitInOrderInsideOneBatchEditWithNoReads() {
        val text = (0 until 30_000).joinToString("") { "%06d\n".format(it) } // 210,000 chars
        assertTrue(LargeTextCommit.commit(conn, text, 64 * 1024, pause = recordPause))
        assertEquals(text, commits.joinToString(""))
        assertTrue(commits.all { wireBytes(it) <= 64 * 1024 })
        // 420,000 UTF-16 bytes in 64 KiB chunks: the budget is filled, not merely respected.
        assertEquals(7, commits.size)
        // No synchronous read between chunks: Chrome leaves it unanswered inside a batch edit.
        assertEquals(listOf("beginBatchEdit") + List(commits.size) { "commitText" } + "endBatchEdit", calls)
        assertTrue("nothing was refused, so nothing waited", pauses.isEmpty())
    }

    @Test
    fun theDefaultChunkFitsTwiceInTheAsyncBinderSpaceForEveryScript() {
        // Each clip is at the store's 512 KB cap. The async binder space is half of ~1 MiB
        // (~508 KiB) and shared by everything queued, so two chunks must fit at once: the next
        // chunk is sent while the previous one may still be in the receiver's queue.
        val asyncSpace = 508 * 1024
        val clips = mapOf(
            "ascii" to "L000000 abcdefghijklmnopqrstuvwxyz01234\n".repeat(13_100), // 1 B UTF-8, 2 B UTF-16
            "hebrew" to "שלום עולם ".repeat(26_000), // 2 B both
            "cjk" to "漢字かな交じり文".repeat(21_800), // 3 B UTF-8
            "emoji" to "😀🎉🚀👍".repeat(32_000), // 4 B per pair both
        )
        for ((script, clip) in clips) {
            commits.clear()
            assertTrue(script, LargeTextCommit.commit(conn, clip))
            assertEquals(script, clip, commits.joinToString(""))
            val biggest = commits.maxOf { wireBytes(it) }
            assertTrue("$script: a $biggest-byte chunk does not fit twice in the async space",
                2 * biggest <= asyncSpace)
            // Chunks fill the byte budget, so the count tracks the wire size, not the char count.
            val minChunks = (wireBytes(clip) + asyncSpace / 2 - 1) / (asyncSpace / 2)
            assertTrue("$script: ${commits.size} chunks, at most ${minChunks + 2} expected",
                commits.size <= minChunks + 2)
        }
    }

    @Test
    fun aChunkNeverEndsBetweenTheHalvesOfASurrogatePair() {
        // 'a' (2 bytes) then emoji (4 bytes each): a char-count boundary at 1,000 would fall
        // between the halves of a pair; the byte budget stops before the pair that does not fit.
        val text = "a" + "😀".repeat(50_000)
        assertTrue(LargeTextCommit.commit(conn, text, 1_000))
        assertEquals(text, commits.joinToString(""))
        for (chunk in commits) {
            assertFalse(Character.isHighSurrogate(chunk.last()))
            assertFalse(Character.isLowSurrogate(chunk.first()))
            assertTrue(wireBytes(chunk) <= 1_000)
        }
        // 2 + 249 × 4 = 998 bytes: 1 + 498 chars; the pair that would reach 1,002 waits.
        assertEquals(499, commits.first().length)
    }

    @Test
    fun chunkEndCountsWireBytesAndBacksOffOnlyForARealPair() {
        // ASCII 2 B (UTF-16), Hebrew 2 B, CJK 3 B (UTF-8), a pair 4 B, a lone surrogate 3 B.
        assertEquals(5, LargeTextCommit.chunkEnd("abcdefgh", 0, 10))
        assertEquals(3, LargeTextCommit.chunkEnd("漢字かな", 0, 10))
        assertEquals(2, LargeTextCommit.chunkEnd("ab😀cd", 0, 6))
        assertEquals(4, LargeTextCommit.chunkEnd("ab😀cd", 0, 8))
        val lone = "ab\uD83D" + "c".repeat(10) // a lone high surrogate is not a pair
        assertEquals(3, LargeTextCommit.chunkEnd(lone, 0, 7))
        assertEquals(lone.length, LargeTextCommit.chunkEnd(lone, 2, 1_000))
    }

    @Test
    fun aTransientlyRefusedChunkIsRetriedInPlaceAndOrderIsKept() {
        // The binder had no async space for the 2nd chunk twice; the 3rd attempt fits.
        refused = { it == 2 || it == 3 }
        val text = (0 until 20_000).joinToString("") { "%06d\n".format(it) }
        assertTrue(LargeTextCommit.commit(conn, text, 64 * 1024, pause = recordPause))
        assertEquals(text, commits.joinToString(""))
        assertEquals(listOf(5L, 10L), pauses)
    }

    @Test
    fun aChunkRefusedOnEveryRetryStopsAndStillClosesTheBatchEdit() {
        refused = { it >= 2 }
        val text = "y".repeat(400_000)
        assertFalse(LargeTextCommit.commit(conn, text, pause = recordPause))
        assertEquals("only the first chunk was delivered", 1, commits.size)
        assertEquals(LargeTextCommit.RETRY_PAUSES_MS, pauses)
        assertEquals(1 + 1 + LargeTextCommit.RETRY_PAUSES_MS.size, calls.count { it == "commitText" })
        assertEquals("endBatchEdit", calls.last())
    }

    /** Size on the larger of the two wire formats: UTF-8 (a lone surrogate encodes as 3) or UTF-16. */
    private fun wireBytes(s: String): Int {
        var utf8 = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                utf8 += 4; i += 2; continue
            }
            utf8 += when { c.code < 0x80 -> 1; c.code < 0x800 -> 2; else -> 3 }
            i++
        }
        return maxOf(utf8, 2 * s.length)
    }
}
