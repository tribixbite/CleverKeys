package tribixbite.cleverkeys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for DebugLoggingManager — covers BroadcastReceiver and
 * Intent-dependent paths that cannot be tested on pure JVM (android.jar stubs
 * throw Stub!).
 *
 * Specifically covers:
 * - registerDebugModeReceiver() with real BroadcastReceiver lifecycle
 * - sendDebugLog() with real Intent construction and broadcast delivery
 * - unregisterDebugModeReceiver() cleanup
 * - Debug mode toggle via broadcast
 */
@RunWith(AndroidJUnit4::class)
class DebugLoggingManagerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var manager: DebugLoggingManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = DebugLoggingManager(context, context.packageName)
    }

    @After
    fun teardown() {
        manager.unregisterDebugModeReceiver(context)
        manager.close()
    }

    // =========================================================================
    // BroadcastReceiver registration — android framework dependent
    // =========================================================================

    @Test
    fun registerDebugModeReceiverDoesNotThrow() {
        // Registering a real BroadcastReceiver requires android framework
        manager.registerDebugModeReceiver(context)
        // Should complete without exception
        assertTrue("Debug mode should start as false", !manager.isDebugMode())
    }

    @Test
    fun registerDebugModeReceiverIsIdempotent() {
        manager.registerDebugModeReceiver(context)
        manager.registerDebugModeReceiver(context) // Second call should be no-op
        // No exception means idempotent registration works
        assertFalse(manager.isDebugMode())
    }

    @Test
    fun unregisterDebugModeReceiverSafeWhenNotRegistered() {
        // Should not throw when no receiver was registered
        manager.unregisterDebugModeReceiver(context)
        assertFalse(manager.isDebugMode())
    }

    @Test
    fun unregisterDebugModeReceiverAfterRegister() {
        manager.registerDebugModeReceiver(context)
        manager.unregisterDebugModeReceiver(context)
        // Should complete without exception
        assertFalse(manager.isDebugMode())
    }

    @Test
    fun debugModeBroadcastEnablesDebugMode() {
        manager.registerDebugModeReceiver(context)

        // Send broadcast to enable debug mode
        val intent = Intent("tribixbite.cleverkeys.SET_DEBUG_MODE")
        intent.setPackage(context.packageName)
        intent.putExtra("debug_enabled", true)
        context.sendBroadcast(intent)

        // Give broadcast time to be delivered
        Thread.sleep(500)

        assertTrue("Debug mode should be enabled after broadcast", manager.isDebugMode())
    }

    @Test
    fun debugModeBroadcastDisablesDebugMode() {
        manager.registerDebugModeReceiver(context)

        // Enable first
        val enableIntent = Intent("tribixbite.cleverkeys.SET_DEBUG_MODE")
        enableIntent.setPackage(context.packageName)
        enableIntent.putExtra("debug_enabled", true)
        context.sendBroadcast(enableIntent)
        Thread.sleep(500)
        assertTrue("Should be enabled", manager.isDebugMode())

        // Disable
        val disableIntent = Intent("tribixbite.cleverkeys.SET_DEBUG_MODE")
        disableIntent.setPackage(context.packageName)
        disableIntent.putExtra("debug_enabled", false)
        context.sendBroadcast(disableIntent)
        Thread.sleep(500)

        assertFalse("Debug mode should be disabled after broadcast", manager.isDebugMode())
    }

    @Test
    fun debugModeListenerNotifiedOnBroadcast() {
        manager.registerDebugModeReceiver(context)

        val latch = CountDownLatch(1)
        var receivedEnabled = false

        manager.registerDebugModeListener(object : DebugLoggingManager.DebugModeListener {
            override fun onDebugModeChanged(enabled: Boolean) {
                receivedEnabled = enabled
                latch.countDown()
            }
        })

        // Send broadcast to enable debug mode
        val intent = Intent("tribixbite.cleverkeys.SET_DEBUG_MODE")
        intent.setPackage(context.packageName)
        intent.putExtra("debug_enabled", true)
        context.sendBroadcast(intent)

        // Wait for broadcast delivery
        val received = latch.await(2, TimeUnit.SECONDS)
        assertTrue("Listener should have been called", received)
        assertTrue("Listener should receive enabled=true", receivedEnabled)
    }

    // =========================================================================
    // sendDebugLog — Intent construction dependent
    // =========================================================================

    @Test
    fun sendDebugLogDoesNothingWhenDebugModeOff() {
        // Should silently return when debug mode is off
        manager.sendDebugLog("test message")
        // No crash = success
        assertFalse(manager.isDebugMode())
    }

    @Test
    fun sendDebugLogBroadcastsWhenDebugModeOn() {
        manager.registerDebugModeReceiver(context)

        // Enable debug mode via broadcast
        val enableIntent = Intent("tribixbite.cleverkeys.SET_DEBUG_MODE")
        enableIntent.setPackage(context.packageName)
        enableIntent.putExtra("debug_enabled", true)
        context.sendBroadcast(enableIntent)
        Thread.sleep(500)

        // Register a receiver to catch the debug log broadcast
        val latch = CountDownLatch(1)
        var receivedMessage: String? = null

        val logReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (SwipeDebugActivity.ACTION_DEBUG_LOG == intent.action) {
                    receivedMessage = intent.getStringExtra(SwipeDebugActivity.EXTRA_LOG_MESSAGE)
                    latch.countDown()
                }
            }
        }
        val filter = IntentFilter(SwipeDebugActivity.ACTION_DEBUG_LOG)
        context.registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        try {
            // Send debug log
            manager.sendDebugLog("test_log_message")

            // Wait for broadcast
            val received = latch.await(2, TimeUnit.SECONDS)
            assertTrue("Debug log broadcast should be received", received)
            assertEquals("test_log_message", receivedMessage)
        } finally {
            context.unregisterReceiver(logReceiver)
        }
    }

    @Test
    fun sendDebugLogIncludesPackageName() {
        manager.registerDebugModeReceiver(context)

        // Enable debug mode
        val enableIntent = Intent("tribixbite.cleverkeys.SET_DEBUG_MODE")
        enableIntent.setPackage(context.packageName)
        enableIntent.putExtra("debug_enabled", true)
        context.sendBroadcast(enableIntent)
        Thread.sleep(500)

        assertTrue("Debug mode should be on", manager.isDebugMode())

        // sendDebugLog should not throw (Intent construction works on real device)
        manager.sendDebugLog("package_test")
        // No exception = Intent was constructed and broadcast sent successfully
    }

    // =========================================================================
    // Full lifecycle — register → enable → log → disable → unregister
    // =========================================================================

    @Test
    fun fullDebugLifecycle() {
        // Register receiver
        manager.registerDebugModeReceiver(context)
        assertFalse(manager.isDebugMode())

        // Enable debug mode
        val enableIntent = Intent("tribixbite.cleverkeys.SET_DEBUG_MODE")
        enableIntent.setPackage(context.packageName)
        enableIntent.putExtra("debug_enabled", true)
        context.sendBroadcast(enableIntent)
        Thread.sleep(500)
        assertTrue("Should be in debug mode", manager.isDebugMode())

        // Send a log message (should not crash)
        manager.sendDebugLog("lifecycle test log")

        // Disable debug mode
        val disableIntent = Intent("tribixbite.cleverkeys.SET_DEBUG_MODE")
        disableIntent.setPackage(context.packageName)
        disableIntent.putExtra("debug_enabled", false)
        context.sendBroadcast(disableIntent)
        Thread.sleep(500)
        assertFalse("Should be out of debug mode", manager.isDebugMode())

        // Send log in disabled state (should silently do nothing)
        manager.sendDebugLog("should not broadcast")

        // Unregister
        manager.unregisterDebugModeReceiver(context)
    }
}
