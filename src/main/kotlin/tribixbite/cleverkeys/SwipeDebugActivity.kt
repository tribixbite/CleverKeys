package tribixbite.cleverkeys

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug activity for swipe typing pipeline analysis.
 * Displays real-time logging of every step in the swipe prediction process.
 */
class SwipeDebugActivity : Activity() {

    private lateinit var logOutput: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var inputText: EditText
    private lateinit var inputScroll: HorizontalScrollView
    private lateinit var backButton: ImageButton
    private lateinit var copyButton: ImageButton
    private lateinit var clearButton: ImageButton
    private lateinit var saveButton: ImageButton

    private val logBuffer = StringBuilder()

    companion object {
        const val ACTION_DEBUG_LOG = "tribixbite.cleverkeys.DEBUG_LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
        private const val REQUEST_CREATE_FILE = 1001
    }

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

        // Find views
        logOutput = findViewById(R.id.log_output)
        logScroll = findViewById(R.id.log_scroll)
        inputText = findViewById(R.id.input_text)
        inputScroll = findViewById(R.id.input_scroll)
        backButton = findViewById(R.id.back_button)
        copyButton = findViewById(R.id.copy_button)
        clearButton = findViewById(R.id.clear_button)
        saveButton = findViewById(R.id.save_button)

        // Back button closes activity
        backButton.setOnClickListener {
            finish()
        }

        copyButton.setOnClickListener {
            copyLogsToClipboard()
        }

        clearButton.setOnClickListener {
            clearLogs()
        }

        saveButton.setOnClickListener {
            saveLogsToFile()
        }

        // Setup input field with auto-scroll to end on text change
        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Auto-scroll to end when text changes
                inputScroll.post {
                    inputScroll.fullScroll(View.FOCUS_RIGHT)
                }
            }
        })

        // Request focus for input text and show keyboard
        inputText.requestFocus()
        inputText.isFocusableInTouchMode = true

        // Auto-show keyboard after a slight delay to ensure view is ready
        inputText.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(inputText, InputMethodManager.SHOW_IMPLICIT)
        }, 200)

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

    private fun saveLogsToFile() {
        if (logBuffer.isEmpty()) {
            Toast.makeText(this, "No logs to save", Toast.LENGTH_SHORT).show()
            return
        }

        // Generate default filename with timestamp
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val filename = "swipe_debug_$timestamp.txt"

        // Open file picker using Storage Access Framework
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, filename)
        }

        try {
            startActivityForResult(intent, REQUEST_CREATE_FILE)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CREATE_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                writeLogsToUri(uri)
            }
        }
    }

    private fun writeLogsToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(logBuffer.toString())
                }
            }
            Toast.makeText(this, "Logs saved successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save logs: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setDebugMode(enabled: Boolean) {
        // Broadcast debug mode state to keyboard service
        val intent = Intent("tribixbite.cleverkeys.SET_DEBUG_MODE").apply {
            setPackage(packageName) // Explicit package for broadcast
            putExtra("debug_enabled", enabled)
        }
        sendBroadcast(intent)
    }
}
