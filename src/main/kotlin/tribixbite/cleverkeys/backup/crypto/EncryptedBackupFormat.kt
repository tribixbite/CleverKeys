package tribixbite.cleverkeys.backup.crypto

/**
 * Thrown for structural / header-level problems with a `CKENC1` container
 * (bad magic, unsupported version, unknown KDF id, out-of-range iteration count,
 * truncated header). Kept **distinct** from the crypto AEAD failures
 * ([javax.crypto.AEADBadTagException]) so callers can produce the right message:
 * header problems have their own copy, while wrong-passphrase and tamper both
 * surface as the AEAD exception (which is deliberately *not* wrapped by this type).
 */
class BackupFormatException(message: String) : Exception(message)

/**
 * The `CKENC1` encrypted-backup container format (design §5).
 *
 * Layout (binary, big-endian), header is [HEADER_LEN] = 51 bytes:
 * ```
 * offset  size  field
 * 0       8     magic: ASCII "CKENC1" + 0x0D 0x0A   (43 4B 45 4E 43 31 0D 0A)
 * 8       1     format_version = 0x01
 * 9       1     content_type: 1=settings JSON, 2=dictionaries JSON,
 *               3=clipboard JSON, 4=clipboard ZIP (media), 5=full-backup ZIP
 * 10      1     kdf_id: 1 = PBKDF2-HMAC-SHA256   (2 reserved for Argon2id)
 * 11      4     kdf_iterations (uint32)
 * 15      16    kdf_salt
 * 31      12    gcm_nonce
 * 43      8     export_timestamp_epoch_millis (informational; AAD-covered)
 * 51      ...   ciphertext ‖ 16-byte GCM tag
 * ```
 * The entire 51-byte header is used as the GCM AAD, so any tampering with these
 * fields invalidates the authentication tag.
 *
 * Pure JVM — no `android.*` imports — so it runs under the `runPureTests` harness.
 */
object EncryptedBackupFormat {

    /** Magic marker: ASCII "CKENC1" + CR LF. The `\r\n` detects text-mode transfer corruption. */
    val MAGIC: ByteArray = byteArrayOf(0x43, 0x4B, 0x45, 0x4E, 0x43, 0x31, 0x0D, 0x0A)

    /** Current (and only supported) container format version. */
    const val FORMAT_VERSION: Byte = 1

    // --- content_type constants (design §5.1) ---
    const val SETTINGS_JSON: Byte = 1
    const val DICTIONARIES_JSON: Byte = 2
    const val CLIPBOARD_JSON: Byte = 3
    const val CLIPBOARD_ZIP: Byte = 4
    const val FULL_BACKUP_ZIP: Byte = 5

    // --- kdf_id constants ---
    const val KDF_PBKDF2_SHA256: Byte = 1

    /**
     * Hard cap on accepted `kdf_iterations` on import, to prevent a malicious
     * header from causing an unbounded-CPU DoS (design §3.2).
     */
    const val MAX_KDF_ITERATIONS: Int = 5_000_000

    // --- fixed field sizes (bytes) ---
    const val SALT_LEN: Int = 16
    const val NONCE_LEN: Int = 12

    /** GCM authentication-tag length in bits. */
    const val TAG_BITS: Int = 128

    /** Total header length in bytes (== AAD length, == ciphertext offset). */
    const val HEADER_LEN: Int = 51

    // --- field offsets within the header ---
    private const val OFF_MAGIC = 0
    private const val OFF_VERSION = 8
    private const val OFF_CONTENT_TYPE = 9
    private const val OFF_KDF_ID = 10
    private const val OFF_ITERATIONS = 11
    private const val OFF_SALT = 15
    private const val OFF_NONCE = 31
    private const val OFF_TIMESTAMP = 43

    /**
     * Parsed / to-be-serialized `CKENC1` header.
     *
     * @param contentType one of the `*_JSON` / `*_ZIP` content-type constants.
     * @param kdfId key-derivation-function id (only [KDF_PBKDF2_SHA256] supported).
     * @param iterations PBKDF2 iteration count; must be in `1..[MAX_KDF_ITERATIONS]`.
     * @param salt exactly [SALT_LEN] bytes.
     * @param nonce exactly [NONCE_LEN] bytes (GCM IV).
     * @param timestampMillis export timestamp (epoch millis; informational, AAD-covered).
     */
    data class Header(
        val contentType: Byte,
        val kdfId: Byte,
        val iterations: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val timestampMillis: Long,
    ) {
        init {
            require(salt.size == SALT_LEN) { "salt must be $SALT_LEN bytes, was ${salt.size}" }
            require(nonce.size == NONCE_LEN) { "nonce must be $NONCE_LEN bytes, was ${nonce.size}" }
        }

        /** Serialize this header to its fixed 51-byte big-endian wire form. */
        fun serialize(): ByteArray {
            val out = ByteArray(HEADER_LEN)
            System.arraycopy(MAGIC, 0, out, OFF_MAGIC, MAGIC.size)
            out[OFF_VERSION] = FORMAT_VERSION
            out[OFF_CONTENT_TYPE] = contentType
            out[OFF_KDF_ID] = kdfId
            writeInt32Be(out, OFF_ITERATIONS, iterations)
            System.arraycopy(salt, 0, out, OFF_SALT, SALT_LEN)
            System.arraycopy(nonce, 0, out, OFF_NONCE, NONCE_LEN)
            writeInt64Be(out, OFF_TIMESTAMP, timestampMillis)
            return out
        }

        // data class over ByteArray fields: override equals/hashCode for content equality.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Header) return false
            return contentType == other.contentType &&
                kdfId == other.kdfId &&
                iterations == other.iterations &&
                salt.contentEquals(other.salt) &&
                nonce.contentEquals(other.nonce) &&
                timestampMillis == other.timestampMillis
        }

        override fun hashCode(): Int {
            var result = contentType.toInt()
            result = 31 * result + kdfId.toInt()
            result = 31 * result + iterations
            result = 31 * result + salt.contentHashCode()
            result = 31 * result + nonce.contentHashCode()
            result = 31 * result + timestampMillis.hashCode()
            return result
        }
    }

    /**
     * Parse and validate a header from the first bytes of a container.
     *
     * Validation order is deliberate — the iteration-count range check happens
     * BEFORE any key derivation could be triggered by a caller, so a malicious
     * `iterations > MAX_KDF_ITERATIONS` header is rejected before burning CPU.
     *
     * @throws BackupFormatException if the buffer is too short, the magic is
     *   wrong, the version is newer than supported, the KDF id is unknown, or the
     *   iteration count is outside `1..[MAX_KDF_ITERATIONS]`.
     */
    fun parse(bytes: ByteArray): Header {
        if (bytes.size < HEADER_LEN) {
            throw BackupFormatException(
                "Truncated backup: header needs $HEADER_LEN bytes, got ${bytes.size}"
            )
        }

        // Magic.
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) {
                throw BackupFormatException("Not a CKENC1 backup: bad magic")
            }
        }

        // Version — a newer version gets a distinct, actionable message.
        val version = bytes[OFF_VERSION]
        if (version > FORMAT_VERSION) {
            throw BackupFormatException(
                "This backup is from a newer CleverKeys version (format v$version); update the app to restore it"
            )
        }
        if (version < FORMAT_VERSION) {
            // No older format has ever shipped; treat as corrupt.
            throw BackupFormatException("Unsupported backup format version: $version")
        }

        val contentType = bytes[OFF_CONTENT_TYPE]

        // KDF id must be one we understand before we try to derive.
        val kdfId = bytes[OFF_KDF_ID]
        if (kdfId != KDF_PBKDF2_SHA256) {
            throw BackupFormatException("Unknown KDF id in backup header: $kdfId")
        }

        // Iterations — validated BEFORE any derivation (DoS guard). Read as
        // uint32; anything above the cap (or non-positive) is rejected here.
        val iterations = readInt32Be(bytes, OFF_ITERATIONS)
        // Note: a uint32 with the high bit set decodes to a negative Int, which
        // is (correctly) rejected by the `< 1` branch below.
        if (iterations < 1 || iterations > MAX_KDF_ITERATIONS) {
            throw BackupFormatException(
                "Backup KDF iteration count out of range (1..$MAX_KDF_ITERATIONS): " +
                    (iterations.toLong() and 0xFFFFFFFFL)
            )
        }

        val salt = bytes.copyOfRange(OFF_SALT, OFF_SALT + SALT_LEN)
        val nonce = bytes.copyOfRange(OFF_NONCE, OFF_NONCE + NONCE_LEN)
        val timestampMillis = readInt64Be(bytes, OFF_TIMESTAMP)

        return Header(contentType, kdfId, iterations, salt, nonce, timestampMillis)
    }

    /** Classification of a candidate backup file by its leading bytes. */
    enum class PayloadKind { ENCRYPTED, PLAINTEXT_JSON, PLAINTEXT_ZIP, UNKNOWN }

    /** Leading bytes of a ZIP local-file header: `PK\x03\x04`. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /**
     * Non-destructively classify a candidate backup file from its bytes (design §5.2):
     * - starts with the full [MAGIC] → [PayloadKind.ENCRYPTED];
     * - starts with `PK\x03\x04` → [PayloadKind.PLAINTEXT_ZIP];
     * - first non-whitespace byte is `{` → [PayloadKind.PLAINTEXT_JSON];
     * - otherwise → [PayloadKind.UNKNOWN].
     *
     * Detection never trusts a file extension. Note a container with a corrupted
     * final magic byte falls through to [PayloadKind.UNKNOWN] (it is neither valid
     * JSON nor a ZIP).
     */
    fun sniff(bytes: ByteArray): PayloadKind {
        if (startsWith(bytes, MAGIC)) return PayloadKind.ENCRYPTED
        if (startsWith(bytes, ZIP_MAGIC)) return PayloadKind.PLAINTEXT_ZIP

        // First non-whitespace byte.
        for (b in bytes) {
            if (isJsonWhitespace(b)) continue
            return if (b == '{'.code.toByte()) PayloadKind.PLAINTEXT_JSON else PayloadKind.UNKNOWN
        }
        return PayloadKind.UNKNOWN
    }

    /** JSON insignificant whitespace: space, tab, CR, LF. */
    private fun isJsonWhitespace(b: Byte): Boolean =
        b == ' '.code.toByte() || b == '\t'.code.toByte() ||
            b == '\r'.code.toByte() || b == '\n'.code.toByte()

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i]) return false
        }
        return true
    }

    private fun writeInt32Be(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 24 and 0xFF).toByte()
        buffer[offset + 1] = (value ushr 16 and 0xFF).toByte()
        buffer[offset + 2] = (value ushr 8 and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readInt32Be(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF shl 24) or
            (buffer[offset + 1].toInt() and 0xFF shl 16) or
            (buffer[offset + 2].toInt() and 0xFF shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)

    private fun writeInt64Be(buffer: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            buffer[offset + i] = (value ushr (56 - i * 8) and 0xFF).toByte()
        }
    }

    private fun readInt64Be(buffer: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
