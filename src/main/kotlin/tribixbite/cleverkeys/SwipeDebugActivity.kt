package tribixbite.cleverkeys

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Debug activity for swipe typing pipeline analysis.
 * Displays real-time logging of every step in the swipe prediction process.
 */
class SwipeDebugActivity : Activity() {

    private lateinit var logOutput: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var inputText: EditText
    private lateinit var copyButton: Button
    private lateinit var clearButton: Button

    private val logBuffer = StringBuilder()

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_DEBUG_LOG == intent.action) {
                val message = intent.getStringExtra(EXTRA_LOG_MESSAGE)
                if (message != null) {
                    appendLog(message)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.swipe_debug_activity)

        logOutput = findViewById(R.id.log_output)
        logScroll = findViewById(R.id.log_scroll)
        inputText = findViewById(R.id.input_text)
        copyButton = findViewById(R.id.copy_button)
        clearButton = findViewById(R.id.clear_button)

        copyButton.setOnClickListener {
            copyLogsToClipboard()
        }

        clearButton.setOnClickListener {
            clearLogs()
        }

        // Request focus for input text
        inputText.requestFocus()
        inputText.isFocusableInTouchMode = true

        // Prevent log output from stealing focus when scrolling
        logScroll.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        logOutput.isFocusable = false

        // Register broadcast receiver for debug logs
        val filter = IntentFilter(ACTION_DEBUG_LOG)
        registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Enable debug mode
        setDebugMode(true)

        appendLog("=== Swipe Debug Session Started ===\n")
        appendLog("Start swiping in the text field above to see pipeline logs.\n\n")
    }

    override fun onDestroy() {
        super.onDestroy()

        // Disable debug mode
        setDebugMode(false)

        // Unregister broadcast receiver
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            logBuffer.append(message)
            logOutput.text = logBuffer.toString()

            // Auto-scroll to bottom
            logScroll.post {
                logScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun clearLogs() {
        logBuffer.setLength(0)
        logOutput.text = "Logs cleared. Waiting for swipe input...\n"
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
    }

    private fun copyLogsToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Swipe Debug Logs", logBuffer.toString())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun setDebugMode(enabled: Boolean) {
        // Broadcast debug mode state to keyboard service
        val intent = Intent("tribixbite.cleverkeys.SET_DEBUG_MODE").apply {
            setPackage(packageName) // Explicit package for broadcast
            putExtra("debug_enabled", enabled)
        }
        sendBroadcast(intent)
    }

    companion object {
        const val ACTION_DEBUG_LOG = "tribixbite.cleverkeys.DEBUG_LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
    }
}
