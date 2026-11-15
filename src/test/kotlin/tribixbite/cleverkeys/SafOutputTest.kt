package tribixbite.cleverkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/** [SafOutput]: truncating mode first, a fallback for providers that refuse it, never null. */
class SafOutputTest {

    @Test
    fun opensInTruncatingModeWhenTheProviderAcceptsIt() {
        val modes = mutableListOf<String>()
        val stream = ByteArrayOutputStream()
        assertSame(stream, SafOutput.open("doc") { mode -> modes += mode; stream })
        assertEquals(listOf("wt"), modes)
    }

    @Test
    fun fallsBackToPlainWriteWhenTruncationIsRefused() {
        for (refusal in listOf<Throwable>(
            IllegalArgumentException("Bad mode"),
            UnsupportedOperationException(),
            FileNotFoundException("Unsupported mode: wt"),
        )) {
            val modes = mutableListOf<String>()
            val stream = ByteArrayOutputStream()
            val opened = SafOutput.open("doc") { mode ->
                modes += mode
                if (mode == "wt") throw refusal else stream
            }
            assertSame(stream, opened)
            assertEquals("after ${refusal.javaClass.simpleName}", listOf("wt", "w"), modes)
        }
    }

    @Test
    fun aNullStreamIsAClassifiedFailure() {
        try {
            SafOutput.open("doc") { null }
            fail("a provider that returns no stream must not look like success")
        } catch (e: ClassifiedIoException) {
            assertEquals(IoFailureReason.FILE_NOT_FOUND, e.reason)
        }
    }

    @Test
    fun aMissingDocumentStillReportsTheProvidersError() {
        val missing = FileNotFoundException("No such document")
        try {
            SafOutput.open("doc") { throw missing }
            fail("expected the provider's error")
        } catch (e: FileNotFoundException) {
            assertSame(missing, e)
        }
    }
}
