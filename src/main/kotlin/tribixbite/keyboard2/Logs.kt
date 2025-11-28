package tribixbite.keyboard2

import android.util.Log
import android.util.LogPrinter
import android.view.inputmethod.EditorInfo

object Logs {
    const val TAG = "tribixbite.keyboard2"

    private var debugLogs: LogPrinter? = null

    @JvmStatic
    fun set_debug_logs(d: Boolean) {
        debugLogs = if (d) LogPrinter(Log.DEBUG, TAG) else null
    }

    @JvmStatic
    fun debug_startup_input_view(info: EditorInfo, conf: Config) {
        debugLogs?.let { logs ->
            info.dump(logs, "")
            info.extras?.let {
                logs.println("extras: ${it}")
            }
            logs.println("swapEnterActionKey: ${conf.swapEnterActionKey}")
            logs.println("actionLabel: ${conf.actionLabel}")
        }
    }

    @JvmStatic
    fun debug_config_migration(from_version: Int, to_version: Int) {
        debug("Migrating config version from $from_version to $to_version")
    }

    @JvmStatic
    fun debug(s: String) {
        debugLogs?.println(s)
    }

    @JvmStatic
    fun exn(msg: String, e: Exception) {
        Log.e(TAG, msg, e)
    }

    @JvmStatic
    fun trace() {
        debugLogs?.println(Log.getStackTraceString(Exception()))
    }
}
