package tribixbite.cleverkeys.backup.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round-trip correctness for [BackupCrypto]. Uses a low iteration count to keep
 * the PBKDF2 work small — iteration count does not affect round-trip semantics,
 * only cost — so the suite stays fast on the ARM64 harness.
 */
class BackupCryptoRoundTripTest {

    private val passphrase = "hunter2-🔒-δοκιμή".toCharArray()
    private val rng = SecureRandom()
    private val lowIters = 2000

    @Test
    fun roundTripsUtf8JsonWithEmojiByteExact() {
        val json = """{"greeting":"héllo 🌍","langs":["日本語","Ελληνικά","🇸🇪"],"n":42}"""
        val plaintext = json.toByteArray(Charsets.UTF_8)

        val container = BackupCrypto.encrypt(
            plaintext,
            passphrase.copyOf(),
            EncryptedBackupFormat.SETTINGS_JSON,
            nowMillis = 1_700_000_000_000L,
            random = rng,
            iterations = lowIters,
        )
        val decrypted = BackupCrypto.decrypt(container, passphrase.copyOf())

        assertArrayEquals(plaintext, decrypted.bytes)
        assertEquals(json, String(decrypted.bytes, Charsets.UTF_8))
    }

    @Test
    fun roundTripsMultiMbBinaryPayloadByteExact() {
        val payload = ByteArray(3 * 1024 * 1024).also { rng.nextBytes(it) }

        val container = BackupCrypto.encrypt(
            payload,
            passphrase.copyOf(),
            EncryptedBackupFormat.FULL_BACKUP_ZIP,
            nowMillis = 123_456_789L,
            random = rng,
            iterations = lowIters,
        )
        val decrypted = BackupCrypto.decrypt(container, passphrase.copyOf())

        assertArrayEquals(payload, decrypted.bytes)
    }

    @Test
    fun contentTypeAndTimestampSurvive() {
        val plaintext = "clipboard".toByteArray()
        val ts = 1_650_000_123_456L

        val container = BackupCrypto.encrypt(
            plaintext,
            passphrase.copyOf(),
            EncryptedBackupFormat.CLIPBOARD_ZIP,
            nowMillis = ts,
            random = rng,
            iterations = lowIters,
        )
        val decrypted = BackupCrypto.decrypt(container, passphrase.copyOf())

        assertEquals(EncryptedBackupFormat.CLIPBOARD_ZIP, decrypted.contentType)
        assertEquals(ts, decrypted.timestampMillis)
    }

    @Test
    fun twoEncryptsOfSamePlaintextDifferButBothDecrypt() {
        val plaintext = "identical plaintext".toByteArray()

        val c1 = BackupCrypto.encrypt(
            plaintext, passphrase.copyOf(), EncryptedBackupFormat.SETTINGS_JSON,
            nowMillis = 1L, random = rng, iterations = lowIters,
        )
        val c2 = BackupCrypto.encrypt(
            plaintext, passphrase.copyOf(), EncryptedBackupFormat.SETTINGS_JSON,
            nowMillis = 1L, random = rng, iterations = lowIters,
        )

        // Fresh salt+nonce → different containers even for the same plaintext/time.
        assertFalse("containers must differ (fresh salt/nonce)", c1.contentEquals(c2))

        assertArrayEquals(plaintext, BackupCrypto.decrypt(c1, passphrase.copyOf()).bytes)
        assertArrayEquals(plaintext, BackupCrypto.decrypt(c2, passphrase.copyOf()).bytes)
    }

    @Test
    fun streamingRoundTripsPayloadAcrossChunkBoundariesByteExact() {
        // > 1 MiB, not a multiple of the 64 KiB chunk size → exercises the final
        // partial chunk + doFinal boundary.
        val payload = ByteArray(1_500_000 + 12345).also { rng.nextBytes(it) }

        val encryptedOut = ByteArrayOutputStream()
        BackupCrypto.encryptStream(
            ByteArrayInputStream(payload),
            encryptedOut,
            passphrase.copyOf(),
            EncryptedBackupFormat.FULL_BACKUP_ZIP,
            nowMillis = 42L,
            random = rng,
            iterations = lowIters,
        )
        val container = encryptedOut.toByteArray()

        // Streaming output must be a valid CKENC1 container decryptable by the
        // buffered path too.
        assertEquals(
            EncryptedBackupFormat.PayloadKind.ENCRYPTED,
            EncryptedBackupFormat.sniff(container),
        )

        val decryptedOut = ByteArrayOutputStream()
        val header = BackupCrypto.decryptToStream(
            ByteArrayInputStream(container),
            decryptedOut,
            passphrase.copyOf(),
        )

        assertArrayEquals(payload, decryptedOut.toByteArray())
        assertEquals(EncryptedBackupFormat.FULL_BACKUP_ZIP, header.contentType)
        assertEquals(42L, header.timestampMillis)

        // And the buffered decrypt agrees with the streaming one.
        assertArrayEquals(payload, BackupCrypto.decrypt(container, passphrase.copyOf()).bytes)
    }

    @Test
    fun streamingDecryptRejectsContainerAndPlaintextOverConfiguredCaps() {
        val payload = ByteArray(256 * 1024) { (it and 0xff).toByte() }
        val encryptedOut = ByteArrayOutputStream()
        BackupCrypto.encryptStream(
            ByteArrayInputStream(payload), encryptedOut, passphrase.copyOf(),
            EncryptedBackupFormat.FULL_BACKUP_ZIP, 42L, rng, lowIters,
        )
        val container = encryptedOut.toByteArray()

        try {
            BackupCrypto.decryptToStream(
                ByteArrayInputStream(container), ByteArrayOutputStream(), passphrase.copyOf(),
                maxPlaintextBytes = payload.size.toLong() - 1,
            )
            fail("Expected plaintext ceiling to reject the decrypted stream")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("Decrypted backup exceeds"))
        }

        try {
            BackupCrypto.decryptToStream(
                ByteArrayInputStream(container), ByteArrayOutputStream(), passphrase.copyOf(),
                maxContainerBytes = container.size.toLong() - 1,
            )
            fail("Expected container ceiling to reject the encrypted stream")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("Encrypted backup exceeds"))
        }
    }
}
