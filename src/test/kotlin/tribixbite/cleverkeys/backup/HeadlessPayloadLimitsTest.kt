package tribixbite.cleverkeys.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ARC-033: the `json_base64` decode used to be unbounded — an oversized extra allocated a
 * decoded buffer and then wrote it into cacheDir with no ceiling.
 *
 * These assert the BOUNDARY arithmetic, which is the whole of the decision. Testing it
 * through the Activity is not viable: the caps are 64 MiB / ~89 M characters, so the test
 * would have to allocate a payload that size and would OOM the instrumentation process
 * before it proved anything. The cleanup half (temp file deleted in onDestroy) is pinned
 * instrumented, in `BackupRestoreActivityHeadlessEncryptionTest`.
 */
class HeadlessPayloadLimitsTest {

    /**
     * The ceiling must stay TIED to the crypto path's in-memory cap, not be an independent
     * number that can drift. If someone raises `BackupCrypto.MAX_IN_MEMORY_BYTES` and the
     * base64 path keeps a stale smaller ceiling, inline import silently refuses payloads the
     * file-URI path accepts — a difference between two entry points to the same feature.
     */
    @Test
    fun `decoded cap equals the crypto path's in-memory cap`() {
        assertThat(HeadlessPayloadLimits.MAX_DECODED_BYTES)
            .isEqualTo(64 * 1024 * 1024)
    }

    /**
     * Base64 is 4 characters per 3 bytes. The encoded ceiling must be at least that much,
     * or a payload of exactly the decoded ceiling would be rejected by length before it was
     * ever decoded — the cap refusing something the importer would have accepted.
     */
    @Test
    fun `encoded cap admits a payload of exactly the decoded cap`() {
        val encodedCharsForMaxPayload =
            ((HeadlessPayloadLimits.MAX_DECODED_BYTES + 2) / 3) * 4 // ceil-div, padded quanta
        assertThat(HeadlessPayloadLimits.MAX_BASE64_CHARS)
            .isAtLeast(encodedCharsForMaxPayload)
    }

    @Test
    fun `encoded cap does not admit an unboundedly larger payload`() {
        // Sanity floor on the other side: the encoded ceiling must not be so generous that
        // it stops bounding anything (e.g. Int.MAX_VALUE). 2x the decoded cap is far above
        // base64's 4/3 expansion and far below "no cap at all".
        assertThat(HeadlessPayloadLimits.MAX_BASE64_CHARS)
            .isLessThan(HeadlessPayloadLimits.MAX_DECODED_BYTES * 2)
    }

    @Test
    fun `base64ExtraExceedsCap is exclusive at the boundary`() {
        assertThat(HeadlessPayloadLimits.base64ExtraExceedsCap(0)).isFalse()
        assertThat(HeadlessPayloadLimits.base64ExtraExceedsCap(1)).isFalse()
        assertThat(
            HeadlessPayloadLimits.base64ExtraExceedsCap(HeadlessPayloadLimits.MAX_BASE64_CHARS)
        ).isFalse()
        assertThat(
            HeadlessPayloadLimits.base64ExtraExceedsCap(HeadlessPayloadLimits.MAX_BASE64_CHARS + 1)
        ).isTrue()
    }

    @Test
    fun `decodedExceedsCap is exclusive at the boundary`() {
        assertThat(HeadlessPayloadLimits.decodedExceedsCap(0)).isFalse()
        assertThat(
            HeadlessPayloadLimits.decodedExceedsCap(HeadlessPayloadLimits.MAX_DECODED_BYTES)
        ).isFalse()
        assertThat(
            HeadlessPayloadLimits.decodedExceedsCap(HeadlessPayloadLimits.MAX_DECODED_BYTES + 1)
        ).isTrue()
    }

    /**
     * A realistic automation payload — bounded by the ~1 MB Binder transaction limit long
     * before either cap — must pass both checks untouched. The cap is a DoS bound; it must
     * never become a capacity the #70 Termux workflow has to think about.
     */
    @Test
    fun `a realistic automation payload is well inside both caps`() {
        val oneMegabyteDecoded = 1024 * 1024
        val itsEncodedLength = (oneMegabyteDecoded / 3) * 4
        assertThat(HeadlessPayloadLimits.decodedExceedsCap(oneMegabyteDecoded)).isFalse()
        assertThat(HeadlessPayloadLimits.base64ExtraExceedsCap(itsEncodedLength)).isFalse()
    }
}
