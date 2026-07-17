package tribixbite.cleverkeys.backup.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's backup passphrase in app-private [SharedPreferences],
 * wrapped at rest with an Android Keystore AES-GCM key (design §4.2, Option C).
 *
 * The real security boundary is the OS sandbox — a zero-permission app cannot
 * read another app's private prefs. The Keystore wrap is defense-in-depth: it
 * keeps the passphrase off disk in cleartext even if the prefs file is exfiltrated
 * by a root/backup-extraction attacker who cannot use the (non-exportable)
 * Keystore key. When the Keystore is unavailable (API 21/22 — Keystore AES-GCM
 * requires API 23; or StrongBox / provider errors on quirky devices) it falls
 * back to storing the passphrase base64-encoded in the same app-private prefs and
 * logs a warning; that is strictly better than today's *plaintext exports*.
 *
 * The wrapping key uses `setUserAuthenticationRequired(false)` because the store
 * must be usable headlessly (Termux `am start` automation) and after reboot without
 * a user unlock gesture.
 *
 * This is the ONLY file in the `crypto` package that touches Android APIs; keep it
 * thin so the format + cipher + KDF layers stay pure-JVM and `runPureTests`-able.
 */
open class BackupPassphraseStore(private val context: Context) {

    companion object {
        private const val TAG = "BackupPassphraseStore"

        /** Dedicated app-private prefs file — kept separate from the main config prefs. */
        const val PREFS_NAME = "ck_backup_passphrase"

        /** Android Keystore alias for the AES-GCM wrapping key. */
        const val KEY_ALIAS = "ck_backup_pp_wrap"

        /**
         * Base64 of the Keystore-wrapped passphrase ciphertext.
         * Load-bearing: added to [tribixbite.cleverkeys.backup.SettingsValidation.INTERNAL_KEYS]
         * so it is NEVER exported (exporting the wrapped passphrase inside the settings
         * backup it protects would be circular).
         */
        const val PREF_CIPHERTEXT = "backup_passphrase_ciphertext"

        /** Base64 of the GCM IV used for the wrap. Internal — never exported. */
        const val PREF_IV = "backup_passphrase_iv"

        /**
         * `"true"` when [PREF_CIPHERTEXT] holds a Keystore-wrapped value, `"false"`
         * when it holds a plain base64 fallback (Keystore unavailable). Lets
         * [getPassphrase] pick the right unwrap path across app restarts.
         */
        private const val PREF_WRAPPED = "backup_passphrase_wrapped"

        private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val WRAP_KEY_BITS = 256
        private const val WRAP_TAG_BITS = 128
        private const val WRAP_IV_LEN = 12
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** `true` if a backup passphrase has been set. */
    open fun hasPassphrase(): Boolean =
        prefs.contains(PREF_CIPHERTEXT)

    /**
     * Return the stored passphrase as a fresh [CharArray], or `null` if none is set
     * or the stored value cannot be unwrapped (corrupt prefs / Keystore key lost after
     * a fingerprint reset). The caller owns the returned array and should zero it after
     * use (best-effort — JVM string interning etc. mean this is hygiene, not a guarantee).
     */
    open fun getPassphrase(): CharArray? {
        if (!hasPassphrase()) return null
        val ciphertextB64 = prefs.getString(PREF_CIPHERTEXT, null) ?: return null
        val wrapped = prefs.getString(PREF_WRAPPED, "false") == "true"

        return try {
            val plaintextBytes = if (wrapped) {
                val ivB64 = prefs.getString(PREF_IV, null)
                    ?: throw IllegalStateException("wrapped passphrase missing IV")
                unwrapWithKeystore(
                    Base64.decode(ciphertextB64, Base64.NO_WRAP),
                    Base64.decode(ivB64, Base64.NO_WRAP),
                )
            } else {
                Base64.decode(ciphertextB64, Base64.NO_WRAP)
            }
            val chars = bytesToChars(plaintextBytes)
            Arrays.fill(plaintextBytes, 0.toByte())
            chars
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recover stored backup passphrase", e)
            null
        }
    }

    /**
     * Persist [passphrase], overwriting any previous value. The caller's array is
     * copied (not retained); this method does not zero the caller's array.
     */
    open fun setPassphrase(passphrase: CharArray) {
        val plaintextBytes = charsToBytes(passphrase)
        try {
            val editor = prefs.edit()
            val wrapped = tryWrapWithKeystore(plaintextBytes)
            if (wrapped != null) {
                editor
                    .putString(PREF_CIPHERTEXT, Base64.encodeToString(wrapped.ciphertext, Base64.NO_WRAP))
                    .putString(PREF_IV, Base64.encodeToString(wrapped.iv, Base64.NO_WRAP))
                    .putString(PREF_WRAPPED, "true")
            } else {
                // Fallback: OS sandbox is still the real boundary. Log so the
                // one-time degradation is visible in bug reports.
                Log.w(
                    TAG,
                    "Android Keystore unavailable — storing backup passphrase base64-obfuscated " +
                        "in app-private prefs (OS sandbox remains the boundary).",
                )
                editor
                    .putString(PREF_CIPHERTEXT, Base64.encodeToString(plaintextBytes, Base64.NO_WRAP))
                    .remove(PREF_IV)
                    .putString(PREF_WRAPPED, "false")
            }
            editor.apply()
        } finally {
            Arrays.fill(plaintextBytes, 0.toByte())
        }
    }

    /** Remove the stored passphrase and, best-effort, delete the Keystore wrapping key. */
    open fun clear() {
        prefs.edit()
            .remove(PREF_CIPHERTEXT)
            .remove(PREF_IV)
            .remove(PREF_WRAPPED)
            .apply()
        try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete Keystore wrapping key (ignored): ${e.message}")
        }
    }

    // ── Keystore wrap/unwrap ────────────────────────────────────────────────

    private data class Wrapped(val ciphertext: ByteArray, val iv: ByteArray)

    /**
     * Wrap [plaintext] with the Keystore AES-GCM key, returning `null` if the
     * Keystore path is unavailable (API < 23 or any provider error) so the caller
     * falls back to base64.
     */
    private fun tryWrapWithKeystore(plaintext: ByteArray): Wrapped? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val key = getOrCreateWrapKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv // Keystore generates a fresh random IV per init
            val ciphertext = cipher.doFinal(plaintext)
            Wrapped(ciphertext, iv)
        } catch (e: Exception) {
            Log.w(TAG, "Keystore wrap failed, falling back to base64: ${e.message}")
            null
        }
    }

    private fun unwrapWithKeystore(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val key = ks.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Keystore wrapping key '$KEY_ALIAS' not found")
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(WRAP_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateWrapKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(WRAP_KEY_BITS)
            // Must be usable headlessly + after reboot without a user unlock.
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    // ── CharArray <-> ByteArray (UTF-8) without going through String ────────

    private fun charsToBytes(chars: CharArray): ByteArray {
        val byteBuffer = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        // Best-effort scrub of the intermediate buffer's backing array.
        if (byteBuffer.hasArray()) Arrays.fill(byteBuffer.array(), 0.toByte())
        return bytes
    }

    private fun bytesToChars(bytes: ByteArray): CharArray {
        val charBuffer = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes))
        val chars = CharArray(charBuffer.remaining())
        charBuffer.get(chars)
        if (charBuffer.hasArray()) Arrays.fill(charBuffer.array(), ' ')
        return chars
    }
}
