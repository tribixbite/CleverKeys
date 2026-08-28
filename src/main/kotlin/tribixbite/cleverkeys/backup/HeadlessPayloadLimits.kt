package tribixbite.cleverkeys.backup

import tribixbite.cleverkeys.backup.crypto.BackupCrypto

/**
 * Size ceilings for the headless (#70) INLINE payload path — the `json_base64` intent extra
 * that `BackupRestoreActivity` decodes to a cache file so automation callers can pipe a
 * backup's bytes straight through `am start` without touching scoped storage.
 *
 * Extracted as a pure-JVM object (ARC-033) for one reason: the caps are ~64/89 MB, so a test
 * that exercised them through the real Activity would have to ALLOCATE a payload that large
 * and would OOM the instrumentation process. The decision is trivial arithmetic; putting it
 * here lets a pure test check both boundaries with plain integers. Same rationale as
 * `PrivateCopyIntentParser`, which likewise re-checks a cap the caller already applies so the
 * rule itself stays unit-testable.
 *
 * DERIVATION — the ceiling is not a new policy number, it is the EXISTING one restated in the
 * encoded domain. [BackupCrypto.MAX_IN_MEMORY_BYTES] (64 MiB) is already the largest payload
 * the decrypt/import path will hold in memory; anything bigger is refused downstream anyway
 * (`BackupRestoreManager` rejects it while reading, `BackupCrypto.decrypt` rejects the
 * container). Capping the base64 extra at the same effective size therefore cannot reject a
 * payload the importer would have accepted — it only stops the decode from allocating, and
 * the cache file from absorbing, bytes that were always going to be thrown away.
 */
object HeadlessPayloadLimits {

    /**
     * Largest DECODED payload accepted from a `json_base64` extra, in bytes.
     * Deliberately identical to [BackupCrypto.MAX_IN_MEMORY_BYTES] (see the derivation above).
     */
    const val MAX_DECODED_BYTES: Int = BackupCrypto.MAX_IN_MEMORY_BYTES

    /**
     * Largest ENCODED payload accepted, in base64 characters.
     *
     * Checked FIRST, before `Base64.decode`, because the decode is what allocates the second
     * (decoded-size) buffer — rejecting after it would defeat the point. Base64 emits 4
     * characters per 3 input bytes; `+ 4` admits the final padded quantum so a payload of
     * exactly [MAX_DECODED_BYTES] can never be rejected by rounding.
     *
     * NOTE this is an UPPER bound on the encoded form only: `Base64.DEFAULT` output also
     * carries line breaks, so a legitimate payload near the ceiling may exceed this and be
     * rejected by length while its decoded size would have fit. That is acceptable — the
     * ceiling is a DoS bound, not a documented capacity, and real automation payloads are
     * bounded far lower by the Binder transaction limit (~1 MB) long before either cap.
     */
    const val MAX_BASE64_CHARS: Int = (MAX_DECODED_BYTES / 3) * 4 + 4

    /** True when a `json_base64` extra of [encodedChars] characters must be refused undecoded. */
    fun base64ExtraExceedsCap(encodedChars: Int): Boolean = encodedChars > MAX_BASE64_CHARS

    /** True when an already-decoded payload of [decodedBytes] must be refused before it is written. */
    fun decodedExceedsCap(decodedBytes: Int): Boolean = decodedBytes > MAX_DECODED_BYTES
}
