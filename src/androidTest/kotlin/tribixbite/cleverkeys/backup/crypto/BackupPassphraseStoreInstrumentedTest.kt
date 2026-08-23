package tribixbite.cleverkeys.backup.crypto

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val expected = "device-only-🔒".toCharArray()
        store.setPassphrase(expected)
        assertEquals(BackupPassphraseStore.ProtectionState.ANDROID_KEYSTORE, store.protectionState())
        assertArrayEquals(expected, store.getPassphrase())

        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry(BackupPassphraseStore.KEY_ALIAS)
        assertNull("wrapped data must not degrade after key loss", store.getPassphrase())
        assertEquals(BackupPassphraseStore.ProtectionState.ANDROID_KEYSTORE, store.protectionState())
    }
}
