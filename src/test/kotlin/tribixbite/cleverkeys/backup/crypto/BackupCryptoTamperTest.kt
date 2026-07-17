package tribixbite.cleverkeys.backup.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tamper / negative-path tests for [BackupCrypto]. The core guarantee under test:
 * decrypt either returns the exact original plaintext, or throws — it must NEVER
 * return altered plaintext.
 *
 * - Body / tag / any AAD-covered header field tamper → [AEADBadTagException].
 * - Structurally-invalid header (unknown version/kdf, iterations over cap,
 *   truncation) → [BackupFormatException], thrown BEFORE key derivation for the
 *   iteration-cap case.
 */
class BackupCryptoTamperTest {

    private val passphrase = "s3cret-passphrase".toCharArray()
    private val rng = SecureRandom()
    private val lowIters = 2000
    private val plaintext = "the quick brown fox jumps over the lazy dog".toByteArray()

    private fun makeContainer(): ByteArray = BackupCrypto.encrypt(
        plaintext,
        passphrase.copyOf(),
        EncryptedBackupFormat.SETTINGS_JSON,
        nowMillis = 1_700_000_000_000L,
        random = rng,
        iterations = lowIters,
    )

    /** Flip a single bit in the byte at [index]. */
    private fun flipBit(container: ByteArray, index: Int, bit: Int = 0): ByteArray {
        val copy = container.copyOf()
        copy[index] = (copy[index].toInt() xor (1 shl bit)).toByte()
        return copy
    }

    private fun assertAeadFailure(container: ByteArray, msg: String) {
        try {
            BackupCrypto.decrypt(container, passphrase.copyOf())
            fail("$msg: expected AEADBadTagException, but decrypt returned")
        } catch (expected: AEADBadTagException) {
            // correct
        }
    }

    private fun assertFormatFailure(container: ByteArray, msg: String) {
        try {
            BackupCrypto.decrypt(container, passphrase.copyOf())
            fail("$msg: expected BackupFormatException, but decrypt returned")
        } catch (expected: BackupFormatException) {
            // correct
        }
    }

    @Test
    fun ciphertextBodyBitFlipFailsTag() {
        val container = makeContainer()
        // A byte well inside the ciphertext body (past the 51-byte header).
        assertAeadFailure(flipBit(container, EncryptedBackupFormat.HEADER_LEN + 3), "body flip")
    }

    @Test
    fun tagRegionBitFlipFailsTag() {
        val container = makeContainer()
        // Last byte is inside the 16-byte GCM tag.
        assertAeadFailure(flipBit(container, container.size - 1, bit = 5), "tag flip")
    }

    @Test
    fun contentTypeFieldTamperFailsTagAsAad() {
        val container = makeContainer()
        // content_type is at offset 9 (AAD-covered, structurally valid value).
        val tampered = container.copyOf()
        tampered[9] = EncryptedBackupFormat.CLIPBOARD_ZIP // was SETTINGS_JSON
        assertAeadFailure(tampered, "content_type AAD tamper")
    }

    @Test
    fun saltFieldTamperFailsTagAsAad() {
        val container = makeContainer()
        // salt occupies [15, 31); flipping a bit changes the derived key AND the AAD.
        assertAeadFailure(flipBit(container, 15), "salt tamper")
    }

    @Test
    fun nonceFieldTamperFailsTagAsAad() {
        val container = makeContainer()
        // nonce occupies [31, 43).
        assertAeadFailure(flipBit(container, 31), "nonce tamper")
    }

    @Test
    fun timestampFieldTamperFailsTagAsAad() {
        val container = makeContainer()
        // timestamp occupies [43, 51) — informational but AAD-covered.
        assertAeadFailure(flipBit(container, 43), "timestamp tamper")
    }

    @Test
    fun iterationsFieldTamperWithinCapFailsTagAsAad() {
        val container = makeContainer()
        // iterations at [11,15). Flip the low bit → still within 1..cap, so it
        // passes the format check and instead fails the AAD/tag (and derives a
        // different key). Guarantees no wrong-plaintext leak.
        assertAeadFailure(flipBit(container, 14, bit = 0), "iterations within-cap tamper")
    }

    @Test
    fun versionFieldTamperIsFormatError() {
        val container = makeContainer()
        // version at offset 8. Set to FORMAT_VERSION+1 → "newer version" format error,
        // thrown by the header parse before any crypto.
        val tampered = container.copyOf()
        tampered[8] = (EncryptedBackupFormat.FORMAT_VERSION + 1).toByte()
        assertFormatFailure(tampered, "version bump")
    }

    @Test
    fun kdfIdFieldTamperIsFormatError() {
        val container = makeContainer()
        // kdf_id at offset 10. An unknown id is a structural error.
        val tampered = container.copyOf()
        tampered[10] = 99
        assertFormatFailure(tampered, "unknown kdf_id")
    }

    @Test
    fun truncatedContainerFailsCleanly() {
        val container = makeContainer()
        // Chop off the tag + some ciphertext → doFinal cannot verify → AEAD failure.
        val truncated = container.copyOf(container.size - 20)
        assertAeadFailure(truncated, "truncated ciphertext/tag")

        // Chop below the header length → clean BackupFormatException.
        val headerTruncated = container.copyOf(EncryptedBackupFormat.HEADER_LEN - 5)
        assertFormatFailure(headerTruncated, "truncated header")
    }

    @Test
    fun wrongPassphraseFailsWithAead() {
        val container = makeContainer()
        try {
            BackupCrypto.decrypt(container, "totally-wrong-password".toCharArray())
            fail("wrong passphrase: expected AEADBadTagException")
        } catch (expected: AEADBadTagException) {
            // correct — cryptographically indistinguishable from tamper.
        }
    }

    /**
     * iterations = MAX+1 must be rejected by the pre-KDF header check (a
     * [BackupFormatException]). Because that exception can ONLY be produced by the
     * format-level guard (the crypto path throws AEADBadTagException, a different
     * type), catching BackupFormatException proves the rejection happened before
     * key derivation.
     */
    @Test
    fun iterationsAboveCapRejectedBeforeKdf() {
        val container = makeContainer()
        val tampered = container.copyOf()
        val over = EncryptedBackupFormat.MAX_KDF_ITERATIONS + 1
        tampered[11] = (over ushr 24 and 0xFF).toByte()
        tampered[12] = (over ushr 16 and 0xFF).toByte()
        tampered[13] = (over ushr 8 and 0xFF).toByte()
        tampered[14] = (over and 0xFF).toByte()

        try {
            BackupCrypto.decrypt(tampered, passphrase.copyOf())
            fail("iterations over cap: expected BackupFormatException")
        } catch (expected: BackupFormatException) {
            assertTrue(
                "message should reference the range",
                expected.message?.contains("range") == true,
            )
        }
    }

    @Test
    fun everyHeaderFieldTamperNeverLeaksWrongPlaintext() {
        val container = makeContainer()
        // Sweep a bit-flip through the whole header; each must throw (never return).
        for (index in 0 until EncryptedBackupFormat.HEADER_LEN) {
            val tampered = flipBit(container, index)
            var returned = false
            try {
                BackupCrypto.decrypt(tampered, passphrase.copyOf())
                returned = true
            } catch (_: AEADBadTagException) {
                // acceptable
            } catch (_: BackupFormatException) {
                // acceptable (magic/version/kdf/iterations structural flips)
            }
            assertFalse("header bit-flip at index $index must not decrypt successfully", returned)
        }
        // Sanity: the pristine container still decrypts to the exact plaintext.
        assertEquals(
            String(plaintext),
            String(BackupCrypto.decrypt(container, passphrase.copyOf()).bytes),
        )
    }
}
