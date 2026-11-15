package tribixbite.cleverkeys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * [PrivateReceivers.register] per API level (review 2026-11-14 M3): the not-exported flag only
 * where Android honours it (33+), the app's signature permission everywhere below, so a foreign
 * app's broadcast is never delivered.
 */
class PrivateReceiversTest {

    private val context = mockk<Context>(relaxed = true)
    private val receiver = mockk<BroadcastReceiver>(relaxed = true)
    private val filter = mockk<IntentFilter>(relaxed = true)

    init {
        every { context.registerReceiver(any(), any(), any<Int>()) } returns null
        every { context.registerReceiver(any(), any(), any<String>(), any()) } returns null
    }

    @Test
    fun api33UsesTheNotExportedFlag() {
        PrivateReceivers.register(context, receiver, filter, sdk = 33)
        verify(exactly = 1) { context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) }
        verify(exactly = 0) { context.registerReceiver(any(), any(), any<String>(), any()) }
    }

    @Test
    fun api26To32UseTheSignaturePermission() {
        for (sdk in 26..32) {
            PrivateReceivers.register(context, receiver, filter, sdk = sdk)
        }
        verify(exactly = 7) {
            context.registerReceiver(receiver, filter, DebugLoggingManager.PERMISSION_SET_DEBUG_MODE, null)
        }
        verify(exactly = 0) { context.registerReceiver(any(), any(), any<Int>()) }
    }

    @Test
    fun api24And25UseTheSignaturePermission() {
        PrivateReceivers.register(context, receiver, filter, sdk = 24)
        PrivateReceivers.register(context, receiver, filter, sdk = 25)
        verify(exactly = 2) {
            context.registerReceiver(receiver, filter, DebugLoggingManager.PERMISSION_SET_DEBUG_MODE, null)
        }
    }
}
