package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import tribixbite.cleverkeys.ml.PlaygroundPayload
import tribixbite.cleverkeys.ml.PlaygroundTraceRecorder
import tribixbite.cleverkeys.ml.SwipeMLDataStore
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Swipe Playground — the swipe-testing surface for developers and data donation.
 *
 * While this screen is open the IME runs in debug mode and, for every swipe typed into
 * the test field, the playground:
 *  - shows the committed word, decoder engine, layout, decode latency and the full
 *    candidate ranking with scores (live panel + scrolling log);
 *  - records the trace — raw points (x, y, t), the active layout's per-key geometry,
 *    the candidate ranking and the committed word — into [SwipeMLDataStore]
 *    (source `"playground"`; see [PlaygroundTraceRecorder] for the explicit-session
 *    privacy rationale and duplicate-avoidance vs the gated global collection);
 *  - offers Export (JSON file + ACTION_SEND share sheet, absolute path shown for
 *    `adb pull`) and Clear (playground-only or all trace rows).
 *
 * Recording is playground-local: it starts when this activity enables debug mode in
 * [onCreate] and stops when [onDestroy] disables it. The UI discloses that recorded
 * traces contain the words the user swipes.
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
    private lateinit var resultWord: TextView
    private lateinit var resultMeta: TextView
    private lateinit var candidatesList: TextView
    private lateinit var traceCount: TextView
    private lateinit var exportTracesButton: Button
    private lateinit var clearTracesButton: Button

    private val logBuffer = StringBuilder()

    // DB work (counts, export, clear) off the main thread; single-threaded so the
    // count shown after an export/clear reflects that operation's outcome.
    private val dbExecutor = Executors.newSingleThreadExecutor()

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

    /** One decoded swipe: update the live panel, append the log block, refresh counts. */
    private val swipeResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (PlaygroundTraceRecorder.ACTION_SWIPE_RESULT != intent.action) return
            val raw = intent.getStringExtra(PlaygroundTraceRecorder.EXTRA_PAYLOAD) ?: return
            try {
                val payload = JSONObject(raw)
                showSwipeResult(payload)
                appendLog(PlaygroundPayload.formatLogBlock(payload))
                refreshTraceCount()
            } catch (e: Exception) {
                appendLog("(malformed playground payload: ${e.message})\n")
            }
        }
    }

    // Context.RECEIVER_NOT_EXPORTED is a compile-time-inlined int constant (public API 33).
    // The flag value is honored by registerReceiver from API 26 onward, which is why it's
    // used inside the SDK_INT >= O guard below; on API 21-25 the 3-arg form is used.
    @SuppressLint("InlinedApi")
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
        resultWord = findViewById(R.id.result_word)
        resultMeta = findViewById(R.id.result_meta)
        candidatesList = findViewById(R.id.candidates_list)
        traceCount = findViewById(R.id.trace_count)
        exportTracesButton = findViewById(R.id.export_traces_button)
        clearTracesButton = findViewById(R.id.clear_traces_button)

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

        exportTracesButton.setOnClickListener {
            exportRecordedTraces()
        }

        clearTracesButton.setOnClickListener {
            confirmClearRecordedTraces()
        }

        // Setup input field with auto-scroll behavior
        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Scroll to show cursor position (usually end of text)
                // Use scrollTo with cursor position instead of fullScroll for better control
                inputScroll.post {
                    // Scroll to show end of text when content overflows
                    // When text fits, scrollX=0 (show from start)
                    // When text overflows, scroll to show end
                    val textWidth = inputText.paint.measureText(s?.toString() ?: "")
                    val paddingTotal = inputText.paddingStart + inputText.paddingEnd
                    val scrollX = (textWidth + paddingTotal - inputScroll.width).coerceAtLeast(0f).toInt()
                    inputScroll.scrollTo(scrollX, 0)
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

        // Register broadcast receivers: raw pipeline log lines + per-swipe result payloads.
        // RECEIVER_NOT_EXPORTED (4-arg registerReceiver) requires API 26. On API 21-25 use
        // the 3-arg form; an app-internal broadcast is not reachable by other apps pre-26.
        val filter = IntentFilter(ACTION_DEBUG_LOG)
        val resultFilter = IntentFilter(PlaygroundTraceRecorder.ACTION_SWIPE_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(swipeResultReceiver, resultFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(logReceiver, filter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(swipeResultReceiver, resultFilter)
        }

        // Enable debug mode — this is ALSO the playground-recording switch: the IME only
        // records/broadcasts playground traces while debug mode is on (see
        // PlaygroundTraceRecorder), so recording is scoped to this screen's lifetime.
        setDebugMode(true)

        appendLog("=== Swipe Playground Session Started ===\n")
        appendLog("Swipe in the text field above. Each swipe shows its candidate ranking\n")
        appendLog("and is recorded (points + key geometry + ranking + committed word).\n\n")
        refreshTraceCount()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Disable debug mode (also stops playground trace recording)
        setDebugMode(false)

        // Unregister broadcast receivers
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        try {
            unregisterReceiver(swipeResultReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        dbExecutor.shutdown()
    }

    // SetTextI18n: this is the Swipe Debug Log viewer — raw diagnostic log text,
    // not localized product UI.
    @SuppressLint("SetTextI18n")
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

    // Debug-only tool: raw diagnostic text is intentionally not localized.
    @android.annotation.SuppressLint("SetTextI18n")
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

    // ── Swipe Playground: live panel + trace recording controls ──────────────────────

    /** Render one decoded swipe's payload into the panel above the log. */
    // SetTextI18n: internal diagnostic tool, deliberately not localized (see class KDoc).
    @SuppressLint("SetTextI18n")
    private fun showSwipeResult(payload: JSONObject) {
        runOnUiThread {
            resultWord.text = "→ ${payload.optString("committed_word", "?")}"
            resultMeta.text = PlaygroundPayload.formatMeta(payload)
            val candidates = PlaygroundPayload.formatCandidates(payload)
            candidatesList.text = candidates
            candidatesList.visibility = View.VISIBLE
        }
    }

    /** Refresh the "N playground / M total" recorded-trace counter (off-main query). */
    @SuppressLint("SetTextI18n")
    private fun refreshTraceCount() {
        dbExecutor.execute {
            try {
                val store = SwipeMLDataStore.getInstance(applicationContext)
                val playground = store.countBySource(PlaygroundTraceRecorder.SOURCE_PLAYGROUND)
                val total = store.getStatistics().totalCount
                runOnUiThread {
                    traceCount.text = "$playground playground / $total total traces"
                }
            } catch (e: Exception) {
                runOnUiThread { traceCount.text = "trace count unavailable" }
            }
        }
    }

    /**
     * Write the full JSON export (ALL recorded traces — playground and global collection,
     * each row tagged with its `collection_source`) to app-external storage, print the
     * absolute path for `adb pull`, and offer the file through the ACTION_SEND share sheet.
     */
    private fun exportRecordedTraces() {
        dbExecutor.execute {
            try {
                val file = SwipeMLDataStore.getInstance(applicationContext).exportToJSON()
                runOnUiThread {
                    appendLog("── EXPORT ──\n${file.absolutePath}\n(adb pull that path, or share below)\n\n")
                    Toast.makeText(this, "Exported ${file.name}", Toast.LENGTH_SHORT).show()
                    shareExportedFile(file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Hand the export off via the system share sheet (FileProvider content URI). */
    private fun shareExportedFile(file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, file.name))
        } catch (e: Exception) {
            // The file is still on disk at the logged path — sharing is best-effort.
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Clear recorded traces after confirmation. Two scopes: playground-only (rows this
     * screen recorded) or ALL swipe-ML rows (incl. the gated global collection's).
     */
    private fun confirmClearRecordedTraces() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Clear recorded traces")
            .setMessage(
                "Delete recorded swipe traces from this device?\n\n" +
                    "“Playground only” removes traces recorded on this screen; " +
                    "“All swipe data” also removes traces from the global ML collection."
            )
            .setPositiveButton("Playground only") { _, _ -> clearRecordedTraces(allData = false) }
            .setNegativeButton("All swipe data") { _, _ -> clearRecordedTraces(allData = true) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun clearRecordedTraces(allData: Boolean) {
        dbExecutor.execute {
            try {
                val store = SwipeMLDataStore.getInstance(applicationContext)
                val removed = if (allData) {
                    val count = store.getStatistics().totalCount
                    store.clearAllData()
                    count
                } else {
                    store.deleteBySource(PlaygroundTraceRecorder.SOURCE_PLAYGROUND)
                }
                runOnUiThread {
                    appendLog("── CLEARED $removed trace(s) (${if (allData) "all" else "playground"}) ──\n\n")
                    Toast.makeText(this, "Cleared $removed trace(s)", Toast.LENGTH_SHORT).show()
                }
                refreshTraceCount()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Clear failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
