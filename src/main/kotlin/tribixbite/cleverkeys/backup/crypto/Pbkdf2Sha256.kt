package tribixbite.cleverkeys.backup.crypto

import java.nio.charset.StandardCharsets
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 2898 (PKCS #5 v2.1) PBKDF2 with HMAC-SHA256, implemented directly over
 * [javax.crypto.Mac] because [javax.crypto.SecretKeyFactory]'s
 * `PBKDF2WithHmacSHA256` algorithm is only available from API 26 while the app's
 * `minSdk` is 21 (API 21 ships only `PBKDF2WithHmacSHA1`).
 *
 * `HmacSHA256` has been available since API 1, so this single code path is
 * deterministic across every supported device and the desktop JVM. It is pure
 * JVM (no `android.*` imports) and therefore runs under the ARM64 `runPureTests`
 * harness.
 *
 * The output is byte-identical to the JCE `PBKDF2WithHmacSHA256` reference (see
 * `Pbkdf2Sha256VectorTest`).
 */
object Pbkdf2Sha256 {

    private const val HMAC_ALGORITHM = "HmacSHA256"

    /** Output size of HMAC-SHA256 in bytes (the PRF block size `hLen`). */
    private const val H_LEN = 32

    /**
     * Derive a key of [dkLen] bytes from [password] and [salt] using PBKDF2 with
     * HMAC-SHA256 as the pseudo-random function.
     *
     * @param password the passphrase; encoded to UTF-8 for use as the HMAC key.
     *   The caller retains ownership of the array (it is not modified or cleared
     *   here); the intermediate UTF-8 byte encoding is zeroed best-effort before
     *   return.
     * @param salt cryptographic salt (should be random and unique per derivation).
     * @param iterations PBKDF2 iteration count `c`; must be `> 0`.
     * @param dkLen desired derived-key length in bytes; must be `> 0`.
     * @return a freshly allocated [ByteArray] of length [dkLen].
     * @throws IllegalArgumentException if [iterations] or [dkLen] is not positive.
     */
    fun derive(password: CharArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations > 0) { "iterations must be > 0, was $iterations" }
        require(dkLen > 0) { "dkLen must be > 0, was $dkLen" }

        val passwordBytes = encodeUtf8(password)
        try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(passwordBytes, HMAC_ALGORITHM))

            // Number of hLen-sized output blocks needed (ceil(dkLen / hLen)).
            val blockCount = (dkLen + H_LEN - 1) / H_LEN
            val derivedKey = ByteArray(dkLen)
            var offset = 0

            // Reusable buffer for salt || INT_32_BE(blockIndex) fed into the first
            // PRF invocation of each block.
            val saltWithIndex = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, saltWithIndex, 0, salt.size)

            for (blockIndex in 1..blockCount) {
                writeInt32Be(saltWithIndex, salt.size, blockIndex)

                // U1 = PRF(password, salt || INT_32_BE(blockIndex))
                var u = mac.doFinal(saltWithIndex)
                // T_blockIndex starts as U1, then XORs in U2..Uc.
                val block = u.copyOf(H_LEN)

                // U2..Uc = PRF(password, U_{n-1}); accumulate XOR into block.
                for (iteration in 2..iterations) {
                    u = mac.doFinal(u)
                    for (k in 0 until H_LEN) {
                        block[k] = (block[k].toInt() xor u[k].toInt()).toByte()
                    }
                }

                // Copy this block into the output (last block may be partial).
                val bytesToCopy = minOf(H_LEN, dkLen - offset)
                System.arraycopy(block, 0, derivedKey, offset, bytesToCopy)
                offset += bytesToCopy
            }

            return derivedKey
        } finally {
            // Best-effort scrub of the transient UTF-8 password encoding.
            Arrays.fill(passwordBytes, 0.toByte())
        }
    }

    /**
     * Encode a [CharArray] to UTF-8 bytes without ever materializing an
     * intermediate [String] (so the plaintext password never lands in the String
     * pool). Any transient buffers are zeroed best-effort.
     */
    private fun encodeUtf8(password: CharArray): ByteArray {
        val charBuffer = java.nio.CharBuffer.wrap(password)
        val byteBuffer = StandardCharsets.UTF_8.encode(charBuffer)
        val result = ByteArray(byteBuffer.remaining())
        byteBuffer.get(result)
        // Scrub the encoder's backing array if it is accessible.
        if (byteBuffer.hasArray()) {
            Arrays.fill(byteBuffer.array(), 0.toByte())
        }
        return result
    }

    /** Write [value] as a big-endian 32-bit integer into [buffer] at [offset]. */
    private fun writeInt32Be(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 24 and 0xFF).toByte()
        buffer[offset + 1] = (value ushr 16 and 0xFF).toByte()
        buffer[offset + 2] = (value ushr 8 and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }
}
