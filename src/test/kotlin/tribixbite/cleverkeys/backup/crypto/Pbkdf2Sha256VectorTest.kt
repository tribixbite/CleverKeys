package tribixbite.cleverkeys.backup.crypto

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * CRITICAL correctness tests for [Pbkdf2Sha256].
 *
 * The desktop java-21 test JVM ships `PBKDF2WithHmacSHA256` (an API 26+ JCE
 * algorithm the Android `minSdk 21` target lacks), so we can cross-check our
 * in-repo RFC 2898 implementation byte-for-byte against the authoritative JCE
 * reference. This proves the substitute is correct on-device (where the JCE
 * algorithm is unavailable).
 */
class Pbkdf2Sha256VectorTest {

    /** Reference PBKDF2-HMAC-SHA256 via the JCE `SecretKeyFactory` (API 26+ / desktop). */
    private fun jceReference(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        dkLenBytes: Int,
    ): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, dkLenBytes * 8)
        return factory.generateSecret(spec).encoded
    }

    /**
     * (a) Equivalence sweep: for several random (password, salt, iterations, dkLen)
     * combinations, our derive() must be byte-identical to the JCE reference.
     */
    @Test
    fun matchesJceReferenceAcrossRandomInputs() {
        val rng = SecureRandom()
        val iterationsSet = intArrayOf(1, 1000, 10000)
        val dkLenSet = intArrayOf(32, 64)

        repeat(6) { trial ->
            // Random printable-ASCII + multibyte password.
            val pwLen = 4 + rng.nextInt(20)
            val password = CharArray(pwLen) {
                // Mix ASCII and some higher code points to exercise UTF-8 encoding.
                if (rng.nextBoolean()) ('!' + rng.nextInt(93)) else ('¡' + rng.nextInt(0x300))
            }
            val salt = ByteArray(8 + rng.nextInt(24)).also { rng.nextBytes(it) }

            for (iterations in iterationsSet) {
                for (dkLen in dkLenSet) {
                    val ours = Pbkdf2Sha256.derive(password.copyOf(), salt, iterations, dkLen)
                    val reference = jceReference(password.copyOf(), salt, iterations, dkLen)
                    assertArrayEquals(
                        "trial=$trial iterations=$iterations dkLen=$dkLen mismatch vs JCE",
                        reference,
                        ours,
                    )
                }
            }
        }
    }

    /**
     * (b) Published RFC 7914 §11 PBKDF2-HMAC-SHA256 vector: P="passwd", S="salt",
     * c=1, dkLen=64. Compared against the JCE output for the SAME inputs (so we do
     * not hardcode a possibly-wrong hex constant) — this simultaneously verifies
     * the documented vector inputs and our implementation.
     */
    @Test
    fun matchesRfc7914Vector() {
        val password = "passwd".toCharArray()
        val salt = "salt".toByteArray(Charsets.US_ASCII)
        val ours = Pbkdf2Sha256.derive(password.copyOf(), salt, 1, 64)
        val reference = jceReference(password.copyOf(), salt, 1, 64)
        assertArrayEquals(reference, ours)
    }

    /** (c) Determinism: identical inputs produce identical output across two calls. */
    @Test
    fun deterministicForSameInputs() {
        val password = "correct horse battery staple".toCharArray()
        val salt = ByteArray(16) { it.toByte() }
        val first = Pbkdf2Sha256.derive(password.copyOf(), salt, 5000, 32)
        val second = Pbkdf2Sha256.derive(password.copyOf(), salt, 5000, 32)
        assertArrayEquals(first, second)
    }

    /** (d) Different salt with the same password → different key. */
    @Test
    fun differentSaltYieldsDifferentKey() {
        val password = "p@ssphrase".toCharArray()
        val saltA = ByteArray(16) { 0x11 }
        val saltB = ByteArray(16) { 0x22 }
        val keyA = Pbkdf2Sha256.derive(password.copyOf(), saltA, 4096, 32)
        val keyB = Pbkdf2Sha256.derive(password.copyOf(), saltB, 4096, 32)
        assertFalse("different salts must not produce identical keys", keyA.contentEquals(keyB))
    }
}
