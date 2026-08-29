package tribixbite.cleverkeys.backup.crypto

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupPassphraseStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: BackupPassphraseStore

    @Before fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        store = BackupPassphraseStore(context)
        store.clear()
    }

    @After fun tearDown() {
        if (::store.isInitialized) store.clear()
    }

    @Test fun keystoreRoundTripAndLostKeyFailClosed() {
        // `clear()` in setUp must really have cleared: without this the round trip below
        // could be reading a passphrase a previous test left behind.
        assertFalse("clear() must leave no stored passphrase", store.hasPassphrase())
        assertEquals(BackupPassphraseStore.ProtectionState.NOT_SET, store.protectionState())
        assertNull("no passphrase set means no passphrase returned", store.getPassphrase())

        val expected = "device-only-🔒".toCharArray()
        val callerCopy = expected.copyOf()
        store.setPassphrase(expected)
        assertTrue("setPassphrase must record that a passphrase exists", store.hasPassphrase())
        assertEquals(BackupPassphraseStore.ProtectionState.ANDROID_KEYSTORE, store.protectionState())
        // Documented contract: the caller's array is COPIED, not retained and not zeroed —
        // a store that zeroed it in place would silently break every caller that reuses it.
        assertArrayEquals("setPassphrase must not mutate the caller's array", callerCopy, expected)

        val first = store.getPassphrase()
        assertArrayEquals(expected, first)
        val second = store.getPassphrase()
        assertArrayEquals("a second read must return the same passphrase", expected, second)
        // "Return the stored passphrase as a FRESH CharArray … the caller owns the returned
        // array and should zero it after use" — a shared array would be zeroed under the
        // next caller's feet.
        assertNotSame("each getPassphrase() must hand back its own array", first, second)

        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry(BackupPassphraseStore.KEY_ALIAS)
        assertNull("wrapped data must not degrade after key loss", store.getPassphrase())
        assertNull("fail-closed must be stable, not a one-shot", store.getPassphrase())
        assertEquals(BackupPassphraseStore.ProtectionState.ANDROID_KEYSTORE, store.protectionState())
        // The ciphertext row survives the key loss: the state is "unreadable", never
        // "unset", so Settings can tell the user their stored password is unrecoverable
        // instead of silently presenting an empty field.
        assertTrue("the wrapped ciphertext must survive the key loss", store.hasPassphrase())
    }
}
