package tribixbite.cleverkeys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build

/**
 * Registers a dynamic receiver that only CleverKeys itself may reach (review 2026-11-14 M3).
 *
 * `Context.RECEIVER_NOT_EXPORTED` is honoured only from API 33. On API 26–32 the
 * `registerReceiver(receiver, filter, flags)` overload accepts only
 * RECEIVER_VISIBLE_TO_INSTANT_APPS and ignores the rest, so a receiver registered with the flag
 * there is EXPORTED, like the plain two-argument form. Below 33 the receiver is therefore
 * registered behind [DebugLoggingManager.PERMISSION_SET_DEBUG_MODE], a signature permission the
 * app holds (`<uses-permission>`): delivery requires the SENDER to hold it, which only
 * same-signature packages can. CleverKeys' own broadcasts (`setPackage(packageName)`) qualify.
 *
 * Not for protected system broadcasts (ACTION_USER_UNLOCKED): the system is not a same-signature
 * sender, and only the system can send those anyway.
 */
object PrivateReceivers {

    /** Register [receiver] for [filter], reachable only by this app. [sdk] is injectable for tests. */
    fun register(
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        sdk: Int = Build.VERSION.SDK_INT,
    ) {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, DebugLoggingManager.PERMISSION_SET_DEBUG_MODE, null)
        }
    }
}
