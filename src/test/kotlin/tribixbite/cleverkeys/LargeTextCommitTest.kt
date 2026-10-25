package tribixbite.cleverkeys

import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * [LargeTextCommit]: clipboard text of any stored size reaches the editor as ordered chunks
 * below the binder limit, inside one batch edit, never splitting a surrogate pair, with a
 * synchronous round trip between chunks (Saga 2026-10-25, a 524,000-char entry failed as one
 * transaction). Pure JVM: the connection is a recording [Proxy].
 */
class LargeTextCommitTest {

    private val calls = mutableListOf<String>()
    private val commits = mutableListOf<String>()
    private var failAt = 0

    private val conn = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
    ) { _, method, args ->
        calls += method.name
        when (method.name) {
            "commitText" -> {
                commits += (args!![0] as CharSequence).toString()
                assertEquals("cursor after the text", 1, args[1])
                commits.size != failAt
            }
            "getTextBeforeCursor" -> ""
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
    fun textOfExactlyOneChunkIsNotSplit() {
        val text = "x".repeat(LargeTextCommit.CHUNK_CHARS)
        assertTrue(LargeTextCommit.commit(conn, text))
        assertEquals(1, commits.size)
    }

    @Test
    fun longTextIsSplitInOrderWithABarrierBetweenChunks() {
        val text = (0 until 30_000).joinToString("") { "%06d\n".format(it) } // 210,000 chars
        assertTrue(LargeTextCommit.commit(conn, text))
        assertEquals(text, commits.joinToString(""))
        assertTrue(commits.all { it.length <= LargeTextCommit.CHUNK_CHARS })
        assertEquals((text.length + LargeTextCommit.CHUNK_CHARS - 1) / LargeTextCommit.CHUNK_CHARS, commits.size)
        // begin, then commit / read pairs, the last commit without a read, then end.
        val expected = listOf("beginBatchEdit") +
            List(commits.size - 1) { listOf("commitText", "getTextBeforeCursor") }.flatten() +
            listOf("commitText", "endBatchEdit")
        assertEquals(expected, calls)
    }

    @Test
    fun aChunkNeverEndsBetweenTheHalvesOfASurrogatePair() {
        // 'a' then emoji: every high surrogate sits at an odd index, so a 32 Ki boundary
        // (index 32,767 is the last char of the first chunk) would split a pair.
        val text = "a" + "😀".repeat(50_000)
        assertTrue(LargeTextCommit.commit(conn, text))
        assertEquals(text, commits.joinToString(""))
        for (chunk in commits) {
            assertFalse(Character.isHighSurrogate(chunk.last()))
            assertFalse(Character.isLowSurrogate(chunk.first()))
        }
        assertEquals(LargeTextCommit.CHUNK_CHARS - 1, commits.first().length)
    }

    @Test
    fun chunkEndBacksOffOnlyForARealPair() {
        val lone = "ab\uD83D" + "c".repeat(10) // a lone high surrogate is not a pair
        assertEquals(3, LargeTextCommit.chunkEnd(lone, 0, maxChars = 3))
        val pair = "ab😀cd"
        assertEquals(2, LargeTextCommit.chunkEnd(pair, 0, maxChars = 3))
        assertEquals(pair.length, LargeTextCommit.chunkEnd(pair, 2, maxChars = 100))
    }

    @Test
    fun aRefusedChunkStopsAndStillClosesTheBatchEdit() {
        failAt = 2
        val text = "y".repeat(LargeTextCommit.CHUNK_CHARS * 4)
        assertFalse(LargeTextCommit.commit(conn, text))
        assertEquals(2, commits.size)
        assertEquals("endBatchEdit", calls.last())
    }
}
