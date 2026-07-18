package tribixbite.cleverkeys.backup.crypto

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Header serialization/parsing and payload-sniffing tests for [EncryptedBackupFormat]. */
class EncryptedBackupFormatTest {

    private val rng = SecureRandom()

    private fun sampleHeader(
        contentType: Byte = EncryptedBackupFormat.SETTINGS_JSON,
        iterations: Int = 600_000,
        timestamp: Long = 1_700_000_000_000L,
    ): EncryptedBackupFormat.Header = EncryptedBackupFormat.Header(
        contentType = contentType,
        kdfId = EncryptedBackupFormat.KDF_PBKDF2_SHA256,
        iterations = iterations,
        salt = ByteArray(EncryptedBackupFormat.SALT_LEN).also { rng.nextBytes(it) },
        nonce = ByteArray(EncryptedBackupFormat.NONCE_LEN).also { rng.nextBytes(it) },
        timestampMillis = timestamp,
    )

    @Test
    fun headerSerializeParseRoundTripsEqual() {
        for (ct in listOf(
            EncryptedBackupFormat.SETTINGS_JSON,
            EncryptedBackupFormat.DICTIONARIES_JSON,
            EncryptedBackupFormat.CLIPBOARD_JSON,
            EncryptedBackupFormat.CLIPBOARD_ZIP,
            EncryptedBackupFormat.FULL_BACKUP_ZIP,
        )) {
            val header = sampleHeader(contentType = ct, iterations = 12345, timestamp = -1L)
            val bytes = header.serialize()
            assertEquals("header must serialize to HEADER_LEN bytes", EncryptedBackupFormat.HEADER_LEN, bytes.size)

            val parsed = EncryptedBackupFormat.parse(bytes)
            assertEquals(header, parsed)
            assertEquals(ct, parsed.contentType)
            assertEquals(12345, parsed.iterations)
            assertArrayEquals(header.salt, parsed.salt)
            assertArrayEquals(header.nonce, parsed.nonce)
            assertEquals(-1L, parsed.timestampMillis)
        }
    }

    @Test
    fun serializeStartsWithMagicAndVersion() {
        val bytes = sampleHeader().serialize()
        for (i in EncryptedBackupFormat.MAGIC.indices) {
            assertEquals(EncryptedBackupFormat.MAGIC[i], bytes[i])
        }
        assertEquals(EncryptedBackupFormat.FORMAT_VERSION, bytes[8])
    }

    @Test
    fun sniffClassifiesEncryptedMagic() {
        val container = sampleHeader().serialize() + ByteArray(32)
        assertEquals(
            EncryptedBackupFormat.PayloadKind.ENCRYPTED,
            EncryptedBackupFormat.sniff(container),
        )
    }

    @Test
    fun sniffClassifiesZip() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "rest".toByteArray()
        assertEquals(
            EncryptedBackupFormat.PayloadKind.PLAINTEXT_ZIP,
            EncryptedBackupFormat.sniff(zip),
        )
    }

    @Test
    fun sniffClassifiesJsonIncludingLeadingWhitespace() {
        assertEquals(
            EncryptedBackupFormat.PayloadKind.PLAINTEXT_JSON,
            EncryptedBackupFormat.sniff("""{"a":1}""".toByteArray()),
        )
        assertEquals(
            EncryptedBackupFormat.PayloadKind.PLAINTEXT_JSON,
            EncryptedBackupFormat.sniff("  \n\t {\"a\":1}".toByteArray()),
        )
    }

    @Test
    fun sniffClassifiesGarbageAsUnknown() {
        assertEquals(
            EncryptedBackupFormat.PayloadKind.UNKNOWN,
            EncryptedBackupFormat.sniff("not json, not zip, not magic".toByteArray()),
        )
        assertEquals(
            EncryptedBackupFormat.PayloadKind.UNKNOWN,
            EncryptedBackupFormat.sniff(byteArrayOf(0x00, 0x01, 0x02)),
        )
        // Leading array bracket is NOT accepted as JSON object.
        assertEquals(
            EncryptedBackupFormat.PayloadKind.UNKNOWN,
            EncryptedBackupFormat.sniff("[1,2,3]".toByteArray()),
        )
    }

    @Test
    fun sniffCorruptedFinalMagicByteIsUnknown() {
        // "CKENC1\r" then a WRONG final byte — no longer the magic, and not JSON/ZIP.
        val corrupt = EncryptedBackupFormat.MAGIC.copyOf()
        corrupt[corrupt.size - 1] = 0x00 // was 0x0A
        assertEquals(
            EncryptedBackupFormat.PayloadKind.UNKNOWN,
            EncryptedBackupFormat.sniff(corrupt + ByteArray(16)),
        )
    }

    // ── headless-import fail-closed gate (design §4.3, TOCTOU fix 2026-07-18) ─────

    @Test
    fun rejectAsPlaintextForHeadless_nullHead_failsClosed() {
        // Unopenable / hostile source (null stream) must be REJECTED, not allowed.
        assertTrue(EncryptedBackupFormat.rejectAsPlaintextForHeadless(null))
    }

    @Test
    fun rejectAsPlaintextForHeadless_plaintextAndUnknown_areRejected() {
        // JSON, ZIP, empty, and garbage all fail closed on the headless path.
        assertTrue(EncryptedBackupFormat.rejectAsPlaintextForHeadless("  {\"a\":1}".toByteArray()))
        assertTrue(EncryptedBackupFormat.rejectAsPlaintextForHeadless(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertTrue(EncryptedBackupFormat.rejectAsPlaintextForHeadless(ByteArray(0)))
        assertTrue(EncryptedBackupFormat.rejectAsPlaintextForHeadless(byteArrayOf(0, 1, 2, 3, 4, 5)))
    }

    @Test
    fun rejectAsPlaintextForHeadless_encryptedMagic_isAllowedThrough() {
        // Only a positively-identified CKENC container proceeds to decrypt+authenticate.
        val encryptedHead = EncryptedBackupFormat.MAGIC + ByteArray(16)
        assertEquals(
            "sanity: MAGIC head sniffs as ENCRYPTED",
            EncryptedBackupFormat.PayloadKind.ENCRYPTED,
            EncryptedBackupFormat.sniff(encryptedHead),
        )
        assertTrue(
            "a genuine CKENC head must NOT be rejected",
            !EncryptedBackupFormat.rejectAsPlaintextForHeadless(encryptedHead),
        )
    }

    @Test
    fun parseNewerVersionThrowsNewerVersionError() {
        val bytes = sampleHeader().serialize()
        bytes[8] = (EncryptedBackupFormat.FORMAT_VERSION + 1).toByte()
        try {
            EncryptedBackupFormat.parse(bytes)
            fail("expected BackupFormatException for newer version")
        } catch (e: BackupFormatException) {
            assertTrue(
                "message should mention a newer version, was: ${e.message}",
                e.message?.contains("newer", ignoreCase = true) == true,
            )
        }
    }

    @Test
    fun parseUnknownKdfThrows() {
        val bytes = sampleHeader().serialize()
        bytes[10] = 7
        try {
            EncryptedBackupFormat.parse(bytes)
            fail("expected BackupFormatException for unknown kdf id")
        } catch (e: BackupFormatException) {
            assertTrue(e.message?.contains("KDF", ignoreCase = true) == true)
        }
    }

    @Test
    fun parseIterationsOverCapThrows() {
        val bytes = sampleHeader().serialize()
        val over = EncryptedBackupFormat.MAX_KDF_ITERATIONS + 1
        bytes[11] = (over ushr 24 and 0xFF).toByte()
        bytes[12] = (over ushr 16 and 0xFF).toByte()
        bytes[13] = (over ushr 8 and 0xFF).toByte()
        bytes[14] = (over and 0xFF).toByte()
        try {
            EncryptedBackupFormat.parse(bytes)
            fail("expected BackupFormatException for iterations over cap")
        } catch (e: BackupFormatException) {
            assertTrue(e.message?.contains("range") == true)
        }
    }

    @Test
    fun parseZeroIterationsThrows() {
        val bytes = sampleHeader().serialize()
        bytes[11] = 0; bytes[12] = 0; bytes[13] = 0; bytes[14] = 0
        try {
            EncryptedBackupFormat.parse(bytes)
            fail("expected BackupFormatException for zero iterations")
        } catch (e: BackupFormatException) {
            assertTrue(e.message?.contains("range") == true)
        }
    }

    @Test
    fun parseBadMagicThrows() {
        val bytes = sampleHeader().serialize()
        bytes[0] = 'X'.code.toByte()
        try {
            EncryptedBackupFormat.parse(bytes)
            fail("expected BackupFormatException for bad magic")
        } catch (e: BackupFormatException) {
            assertTrue(e.message?.contains("magic", ignoreCase = true) == true)
        }
    }

    @Test
    fun parseTooShortThrows() {
        try {
            EncryptedBackupFormat.parse(ByteArray(10))
            fail("expected BackupFormatException for short buffer")
        } catch (e: BackupFormatException) {
            assertTrue(e.message?.contains("Truncated", ignoreCase = true) == true)
        }
    }
}
