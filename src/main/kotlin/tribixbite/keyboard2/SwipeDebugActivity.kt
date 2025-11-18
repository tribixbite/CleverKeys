package tribixbite.keyboard2

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug activity for viewing real-time swipe prediction pipeline logs.
 *
 * Opens a dedicated UI with:
 * - Scrollable log output showing every pipeline step
 * - Text input field at bottom for testing keyboard
 * - Copy/Clear buttons for log management
 *
 * Debug logging is gated behind a flag - only enabled when this activity is open.
 * Normal keyboard use elsewhere has no performance impact.
 */
class SwipeDebugActivity : Activity() {

    companion object {
        const val ACTION_DEBUG_LOG = "tribixbite.keyboard2.DEBUG_LOG"
        const val ACTION_SET_DEBUG_MODE = "tribixbite.keyboard2.SET_DEBUG_MODE"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val EXTRA_DEBUG_ENABLED = "debug_enabled"

        private const val MAX_LOG_LINES = 1000
    }

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText

    private val logBuffer = StringBuilder()
    private var lineCount = 0

    private val debugLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_DEBUG_LOG) {
                val message = intent.getStringExtra(EXTRA_LOG_MESSAGE) ?: return
                appendLog(message)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create UI programmatically for dark debug theme
        createDebugUI()

        // Register broadcast receiver for debug logs
        val filter = IntentFilter(ACTION_DEBUG_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(debugLogReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(debugLogReceiver, filter)
        }

        // Enable debug mode in keyboard service
        setDebugMode(true)

        appendLog("=== SwipeDebugActivity Started ===")
        appendLog("Debug logging enabled for prediction pipeline")
        appendLog("Type in the text field below to see logs")
        appendLog("")
    }

    override fun onDestroy() {
        super.onDestroy()

        // Disable debug mode when activity closes
        setDebugMode(false)

        try {
            unregisterReceiver(debugLogReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    private fun createDebugUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E1E1E.toInt()) // Dark background
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Top bar with title and buttons
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF2D2D2D.toInt())
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleText = TextView(this).apply {
            text = "Swipe Debug Log"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val copyButton = Button(this).apply {
            text = "Copy"
            setOnClickListener { copyLogs() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
        }

        val clearButton = Button(this).apply {
            text = "Clear"
            setOnClickListener { clearLogs() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        topBar.addView(titleText)
        topBar.addView(copyButton)
        topBar.addView(clearButton)

        // Scrollable log output area
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(0xFF1E1E1E.toInt())
        }

        logTextView = TextView(this).apply {
            setTextColor(0xFF00FF00.toInt()) // Green terminal text
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
            movementMethod = ScrollingMovementMethod()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(logTextView)

        // Text input field for testing keyboard
        inputField = EditText(this).apply {
            hint = "Type here to test keyboard..."
            setHintTextColor(0xFF888888.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2D2D2D.toInt())
            setPadding(16, 16, 16, 16)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 8, 8, 8)
            }
        }

        rootLayout.addView(topBar)
        rootLayout.addView(scrollView)
        rootLayout.addView(inputField)

        setContentView(rootLayout)
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message\n"

        logBuffer.append(logEntry)
        lineCount++

        // Trim old logs if buffer gets too large
        if (lineCount > MAX_LOG_LINES) {
            val lines = logBuffer.toString().split("\n")
            val trimmed = lines.takeLast(MAX_LOG_LINES - 100).joinToString("\n")
            logBuffer.clear()
            logBuffer.append(trimmed)
            lineCount = MAX_LOG_LINES - 100
        }

        runOnUiThread {
            logTextView.text = logBuffer.toString()
            // Auto-scroll to bottom
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun copyLogs() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Swipe Debug Log", logBuffer.toString())
        clipboard.setPrimaryClip(clip)
        appendLog("--- Logs copied to clipboard ---")
    }

    private fun clearLogs() {
        logBuffer.clear()
        lineCount = 0
        logTextView.text = ""
        appendLog("--- Log cleared ---")
    }

    private fun setDebugMode(enabled: Boolean) {
        val intent = Intent(ACTION_SET_DEBUG_MODE).apply {
            putExtra(EXTRA_DEBUG_ENABLED, enabled)
            setPackage(packageName)
        }
        sendBroadcast(intent)

        // Also set directly via companion object for immediate effect
        DebugLoggingManager.setDebugMode(this, enabled)
    }
}

/**
 * Global debug logging manager.
 *
 * Provides centralized debug mode control that can be checked by any component.
 * When debug mode is off, logging calls are no-ops for zero performance impact.
 */
object DebugLoggingManager {

    @Volatile
    private var debugModeEnabled = false

    private var appContext: Context? = null

    fun setDebugMode(context: Context, enabled: Boolean) {
        appContext = context.applicationContext
        debugModeEnabled = enabled
    }

    fun isDebugModeEnabled(): Boolean = debugModeEnabled

    /**
     * Send a debug log message. Only broadcasts if debug mode is enabled.
     * Call this from prediction pipeline components.
     */
    fun sendDebugLog(message: String) {
        if (!debugModeEnabled) return

        val context = appContext ?: return
        val intent = Intent(SwipeDebugActivity.ACTION_DEBUG_LOG).apply {
            putExtra(SwipeDebugActivity.EXTRA_LOG_MESSAGE, message)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}
