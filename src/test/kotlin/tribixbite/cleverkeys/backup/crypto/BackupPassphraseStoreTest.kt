package tribixbite.cleverkeys.backup.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MockK coverage for [BackupPassphraseStore].
 *
 * The Android Keystore cannot run under the ARM64 mock-test harness, so
 * `Build.VERSION.SDK_INT` (default 0 with the android.jar stubs) forces the
 * base64-in-prefs fallback path — exactly the branch design §4.2 says must remain
 * strictly-better-than-plaintext-exports. We back a real in-memory prefs map so
 * set→get→clear round-trips exercise the actual serialization code, and stub
 * `android.util.Base64` with `java.util.Base64` so the encode/decode is real.
 */
class BackupPassphraseStoreTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private val backing = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0

        // Real base64 so encode(decode(x)) == x through the store's serialization.
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }

        context = io.mockk.mockk(relaxed = true)
        prefs = io.mockk.mockk(relaxed = true)
        editor = io.mockk.mockk(relaxed = true)

        every { context.getSharedPreferences(any(), any()) } returns prefs

        // In-memory prefs backing so writes are visible to subsequent reads.
        every { prefs.edit() } returns editor
        val putKey = slot<String>()
        val putVal = slot<String>()
        every { editor.putString(capture(putKey), capture(putVal)) } answers {
            backing[putKey.captured] = putVal.captured
            editor
        }
        val remKey = slot<String>()
        every { editor.remove(capture(remKey)) } answers {
            backing.remove(remKey.captured)
            editor
        }
        every { editor.apply() } answers { }
        every { editor.commit() } returns true

        val getKey = slot<String>()
        val getDef = slot<String?>()
        every { prefs.getString(capture(getKey), captureNullable(getDef)) } answers {
            if (backing.containsKey(getKey.captured)) backing[getKey.captured] else getDef.captured
        }
        val hasKey = slot<String>()
        every { prefs.contains(capture(hasKey)) } answers { backing.containsKey(hasKey.captured) }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun newStore() = BackupPassphraseStore(context, sdkInt = 22)

    @Test
    fun modernKeystoreFailureDoesNotPersistFallback() {
        val store = BackupPassphraseStore(context, sdkInt = 34)
        org.junit.Assert.assertThrows(BackupPassphraseStore.StorageUnavailableException::class.java) {
            store.setPassphrase("must-not-persist".toCharArray())
        }
        assertThat(backing).doesNotContainKey(BackupPassphraseStore.PREF_CIPHERTEXT)
        assertThat(store.protectionState())
            .isEqualTo(BackupPassphraseStore.ProtectionState.NOT_SET)
    }

    @Test
    fun setThenGet_roundTripsPassphrase() {
        val store = newStore()
        assertThat(store.hasPassphrase()).isFalse()

        store.setPassphrase("correct horse battery".toCharArray())
        assertThat(store.hasPassphrase()).isTrue()
        assertThat(store.protectionState())
            .isEqualTo(BackupPassphraseStore.ProtectionState.LEGACY_APP_PRIVATE)

        val recovered = store.getPassphrase()
        assertThat(recovered).isNotNull()
        assertThat(String(recovered!!)).isEqualTo("correct horse battery")
    }

    @Test
    fun setThenGet_survivesNewStoreInstance() {
        // A fresh store instance (simulating process recreation) reads the same backing.
        newStore().setPassphrase("s3cret-pass".toCharArray())
        val recovered = newStore().getPassphrase()
        assertThat(recovered).isNotNull()
        assertThat(String(recovered!!)).isEqualTo("s3cret-pass")
    }

    @Test
    fun fallbackPath_marksWrappedFalse() {
        // Keystore unavailable (SDK_INT=0) → fallback base64, PREF_WRAPPED="false".
        newStore().setPassphrase("abc12345".toCharArray())
        assertThat(backing[BackupPassphraseStore.PREF_CIPHERTEXT]).isNotNull()
        // Fallback stores plain base64 of the UTF-8 bytes (no IV).
        assertThat(backing).doesNotContainKey(BackupPassphraseStore.PREF_IV)
        val decoded = java.util.Base64.getDecoder()
            .decode(backing[BackupPassphraseStore.PREF_CIPHERTEXT])
        assertThat(String(decoded, Charsets.UTF_8)).isEqualTo("abc12345")
    }

    @Test
    fun getPassphrase_nullWhenUnset() {
        assertThat(newStore().getPassphrase()).isNull()
        assertThat(newStore().hasPassphrase()).isFalse()
    }

    @Test
    fun clear_removesStoredPassphrase() {
        val store = newStore()
        store.setPassphrase("to-be-cleared".toCharArray())
        assertThat(store.hasPassphrase()).isTrue()

        store.clear()
        assertThat(store.hasPassphrase()).isFalse()
        assertThat(store.protectionState())
            .isEqualTo(BackupPassphraseStore.ProtectionState.NOT_SET)
        assertThat(store.getPassphrase()).isNull()
        assertThat(backing).doesNotContainKey(BackupPassphraseStore.PREF_CIPHERTEXT)
    }

    @Test
    fun setPassphrase_overwritesPrevious() {
        val store = newStore()
        store.setPassphrase("first-one".toCharArray())
        store.setPassphrase("second-one".toCharArray())
        assertThat(String(store.getPassphrase()!!)).isEqualTo("second-one")
    }

    @Test
    fun roundTrip_preservesMultiByteUtf8() {
        val store = newStore()
        val pass = "pä55wörd-🔒-Ω".toCharArray()
        store.setPassphrase(pass)
        assertThat(String(store.getPassphrase()!!)).isEqualTo("pä55wörd-🔒-Ω")
    }
}
