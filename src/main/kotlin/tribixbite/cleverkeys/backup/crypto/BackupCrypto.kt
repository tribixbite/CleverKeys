package tribixbite.cleverkeys.backup.crypto

import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM authenticated encryption for the `CKENC1` backup container
 * (design §3). One primitive provides both confidentiality (closes the
 * exfiltration vector) and integrity/authenticity (closes injection: without the
 * key, an attacker cannot forge a payload that passes the GCM tag check).
 *
 * The full 51-byte [EncryptedBackupFormat] header is bound as GCM AAD, so any
 * tampering with the version, content-type, KDF parameters, salt, nonce or
 * timestamp invalidates the tag.
 *
 * Key derivation uses [Pbkdf2Sha256] (PBKDF2-HMAC-SHA256) over the passphrase +
 * a fresh random salt, producing a fresh 32-byte key per file. Because the key is
 * re-derived from a fresh random salt every time, `(key, nonce)` pairs never
 * repeat — GCM nonce reuse is structurally impossible.
 *
 * Pure JVM (`javax.crypto`) — no `android.*` imports — so it runs under the ARM64
 * `runPureTests` harness. A [SecureRandom] is injectable for deterministic tests.
 */
object BackupCrypto {

    private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
    private const val AES_KEY_ALGORITHM = "AES"

    /** Derived AES-256 key length in bytes. */
    private const val KEY_LEN = 32

    /** Streaming chunk size for the explicit `cipher.update()` loop. */
    private const val STREAM_CHUNK = 64 * 1024

    /**
     * Maximum in-memory plaintext / container size for the buffered [encrypt] /
     * [decrypt] entry points (design §6): content types 1–3 are held in memory,
     * so reject over-large containers before allocating / running `doFinal`.
     */
    const val MAX_IN_MEMORY_BYTES: Int = 64 * 1024 * 1024 // 64 MiB

    /** Default PBKDF2 iteration count (OWASP 2023 recommendation for SHA256). */
    const val DEFAULT_ITERATIONS: Int = 600_000

    /**
     * Encrypt [plaintext] into a self-describing `CKENC1` container.
     *
     * A fresh [EncryptedBackupFormat.SALT_LEN]-byte salt and
     * [EncryptedBackupFormat.NONCE_LEN]-byte nonce are drawn from [random]; the
     * 32-byte key is derived from [passphrase] via [Pbkdf2Sha256]. The serialized
     * header is bound as GCM AAD.
     *
     * @return `header || (ciphertext || 16-byte GCM tag)`.
     */
    fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
        contentType: Byte,
        nowMillis: Long,
        random: SecureRandom = SecureRandom(),
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        require(plaintext.size <= MAX_IN_MEMORY_BYTES) {
            "plaintext exceeds in-memory cap ($MAX_IN_MEMORY_BYTES bytes)"
        }

        val salt = ByteArray(EncryptedBackupFormat.SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(EncryptedBackupFormat.NONCE_LEN).also { random.nextBytes(it) }

        val header = EncryptedBackupFormat.Header(
            contentType = contentType,
            kdfId = EncryptedBackupFormat.KDF_PBKDF2_SHA256,
            iterations = iterations,
            salt = salt,
            nonce = nonce,
            timestampMillis = nowMillis,
        )
        val headerBytes = header.serialize()

        val keyBytes = Pbkdf2Sha256.derive(passphrase, salt, iterations, KEY_LEN)
        try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            val keySpec = SecretKeySpec(keyBytes, AES_KEY_ALGORITHM)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                keySpec,
                GCMParameterSpec(EncryptedBackupFormat.TAG_BITS, nonce),
            )
            cipher.updateAAD(headerBytes)
            val ciphertextWithTag = cipher.doFinal(plaintext)

            // Container = header || ciphertext||tag.
            val container = ByteArray(headerBytes.size + ciphertextWithTag.size)
            System.arraycopy(headerBytes, 0, container, 0, headerBytes.size)
            System.arraycopy(ciphertextWithTag, 0, container, headerBytes.size, ciphertextWithTag.size)
            return container
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }

    /**
     * A payload recovered from a `CKENC1` container after successful tag
     * verification.
     */
    data class DecryptedPayload(
        val contentType: Byte,
        val timestampMillis: Long,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecryptedPayload) return false
            return contentType == other.contentType &&
                timestampMillis == other.timestampMillis &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = contentType.toInt()
            result = 31 * result + timestampMillis.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    /**
     * Decrypt and authenticate a buffered `CKENC1` [container].
     *
     * Header structural problems (bad magic, newer version, unknown KDF, iteration
     * count above [EncryptedBackupFormat.MAX_KDF_ITERATIONS], truncation) throw
     * [BackupFormatException] — and the iteration-cap check runs BEFORE any key
     * derivation, so a hostile header cannot trigger an expensive KDF.
     *
     * A wrong passphrase OR any tamper of the AAD/ciphertext/tag surfaces as
     * [javax.crypto.AEADBadTagException] (a subclass of
     * [javax.crypto.BadPaddingException]) from `doFinal`; it is deliberately NOT
     * caught or converted here — the two cases are cryptographically
     * indistinguishable and the caller maps both to a single message.
     *
     * The over-large-container guard is a length check on [container]; the
     * plaintext for content types 1–3 is held in memory.
     */
    fun decrypt(container: ByteArray, passphrase: CharArray): DecryptedPayload {
        // Header parse validates magic/version/kdf/iterations (cap enforced here,
        // before any KDF work) and throws BackupFormatException on any issue.
        val header = EncryptedBackupFormat.parse(container)

        if (container.size > MAX_IN_MEMORY_BYTES + EncryptedBackupFormat.HEADER_LEN) {
            throw BackupFormatException(
                "Encrypted backup too large for in-memory decrypt " +
                    "(> $MAX_IN_MEMORY_BYTES bytes plaintext)"
            )
        }

        val headerBytes = container.copyOfRange(0, EncryptedBackupFormat.HEADER_LEN)
        val ciphertextWithTag = container.copyOfRange(EncryptedBackupFormat.HEADER_LEN, container.size)

        val keyBytes = Pbkdf2Sha256.derive(passphrase, header.salt, header.iterations, KEY_LEN)
        try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            val keySpec = SecretKeySpec(keyBytes, AES_KEY_ALGORITHM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                GCMParameterSpec(EncryptedBackupFormat.TAG_BITS, header.nonce),
            )
            cipher.updateAAD(headerBytes)
            // Throws AEADBadTagException on wrong passphrase OR tamper. Not caught.
            val plaintext = cipher.doFinal(ciphertextWithTag)
            return DecryptedPayload(header.contentType, header.timestampMillis, plaintext)
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }

    /**
     * Streaming encrypt: read all of [input], write a `CKENC1` container to
     * [output]. The header is written first, then the ciphertext is produced with
     * an explicit [STREAM_CHUNK]-sized `cipher.update()` loop and a final
     * `cipher.doFinal()` (which emits the GCM tag). Neither stream is closed here.
     */
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray,
        contentType: Byte,
        nowMillis: Long,
        random: SecureRandom = SecureRandom(),
        iterations: Int = DEFAULT_ITERATIONS,
    ) {
        val salt = ByteArray(EncryptedBackupFormat.SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(EncryptedBackupFormat.NONCE_LEN).also { random.nextBytes(it) }

        val header = EncryptedBackupFormat.Header(
            contentType = contentType,
            kdfId = EncryptedBackupFormat.KDF_PBKDF2_SHA256,
            iterations = iterations,
            salt = salt,
            nonce = nonce,
            timestampMillis = nowMillis,
        )
        val headerBytes = header.serialize()

        val keyBytes = Pbkdf2Sha256.derive(passphrase, salt, iterations, KEY_LEN)
        try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            val keySpec = SecretKeySpec(keyBytes, AES_KEY_ALGORITHM)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                keySpec,
                GCMParameterSpec(EncryptedBackupFormat.TAG_BITS, nonce),
            )
            cipher.updateAAD(headerBytes)

            // Header is plaintext and safe to emit up front.
            output.write(headerBytes)

            val buffer = ByteArray(STREAM_CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    val chunk = cipher.update(buffer, 0, read)
                    if (chunk != null && chunk.isNotEmpty()) output.write(chunk)
                }
            }
            val finalChunk = cipher.doFinal() // emits remaining ciphertext + tag
            if (finalChunk != null && finalChunk.isNotEmpty()) output.write(finalChunk)
            output.flush()
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }

    /**
     * Streaming decrypt: read a `CKENC1` container from [container] and write the
     * verified plaintext to [output], returning the parsed [EncryptedBackupFormat.Header].
     *
     * Uses an explicit [STREAM_CHUNK] `cipher.update()` loop then `cipher.doFinal()`
     * (never [javax.crypto.CipherInputStream], which has historically swallowed
     * [javax.crypto.AEADBadTagException] on Android/JDK and released unauthenticated
     * plaintext incrementally).
     *
     * **Contract:** bytes written to [output] are NOT authenticated until this
     * method returns without throwing. If `doFinal()` throws
     * [javax.crypto.AEADBadTagException] (wrong passphrase or tamper), some
     * unauthenticated plaintext may already have been written — the Android
     * integration therefore writes to a temp file and only consumes it *after* a
     * clean return. Header problems throw [BackupFormatException] before any
     * plaintext is written. Neither stream is closed here.
     */
    fun decryptToStream(
        container: InputStream,
        output: OutputStream,
        passphrase: CharArray,
        maxContainerBytes: Long = Long.MAX_VALUE,
        maxPlaintextBytes: Long = Long.MAX_VALUE,
    ): EncryptedBackupFormat.Header {
        require(maxContainerBytes >= EncryptedBackupFormat.HEADER_LEN) {
            "maxContainerBytes must fit the encrypted header"
        }
        require(maxPlaintextBytes >= 0L) { "maxPlaintextBytes must be non-negative" }
        // Read exactly the header bytes, then parse (enforces the iteration cap
        // before any KDF work).
        val headerBytes = readExactly(container, EncryptedBackupFormat.HEADER_LEN)
        if (headerBytes.size < EncryptedBackupFormat.HEADER_LEN) {
            throw BackupFormatException(
                "Truncated backup: header needs ${EncryptedBackupFormat.HEADER_LEN} bytes, " +
                    "got ${headerBytes.size}"
            )
        }
        val header = EncryptedBackupFormat.parse(headerBytes)

        val keyBytes = Pbkdf2Sha256.derive(passphrase, header.salt, header.iterations, KEY_LEN)
        try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            val keySpec = SecretKeySpec(keyBytes, AES_KEY_ALGORITHM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                GCMParameterSpec(EncryptedBackupFormat.TAG_BITS, header.nonce),
            )
            cipher.updateAAD(headerBytes)

            val buffer = ByteArray(STREAM_CHUNK)
            var containerBytes = EncryptedBackupFormat.HEADER_LEN.toLong()
            var plaintextBytes = 0L

            fun writePlaintext(chunk: ByteArray?) {
                if (chunk == null || chunk.isEmpty()) return
                plaintextBytes += chunk.size
                if (plaintextBytes > maxPlaintextBytes) {
                    throw IOException(
                        "Decrypted backup exceeds $maxPlaintextBytes byte limit"
                    )
                }
                output.write(chunk)
            }

            while (true) {
                val read = container.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    containerBytes += read
                    if (containerBytes > maxContainerBytes) {
                        throw IOException(
                            "Encrypted backup exceeds $maxContainerBytes byte limit"
                        )
                    }
                    writePlaintext(cipher.update(buffer, 0, read))
                }
            }
            // Verifies the tag; throws AEADBadTagException on wrong key / tamper.
            writePlaintext(cipher.doFinal())
            output.flush()
            return header
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }

    /**
     * Read up to [count] bytes from [input], blocking until [count] bytes are read
     * or EOF. Returns a possibly-shorter array on premature EOF (caller checks).
     */
    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = input.read(buffer, filled, count - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == count) buffer else buffer.copyOf(filled)
    }
}
