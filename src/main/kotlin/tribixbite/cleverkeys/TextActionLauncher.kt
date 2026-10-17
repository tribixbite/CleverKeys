package tribixbite.cleverkeys

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.inputmethod.InputConnection

/**
 * Starts the IME's own text-action activities: the process-text trampoline behind the
 * `textAssist`/`replaceText` editing keys ([launchForResult], result written back by
 * `KeyEventHandler.deliverProcessTextResult`) and the share chooser ([share]). The ONE
 * implementation for layout keys and custom mappings alike (`KeyEventHandler.handleEditingKey`).
 *
 * Most editors do not implement `android.R.id.textAssist`/`replaceText`/`shareText`, so these
 * are app choosers; the bare context-menu id is only the fallback when the trampoline cannot
 * start.
 */
internal object TextActionLauncher {

    private const val TAG = "TextActionLauncher"

    /**
     * shareText: an ACTION_SEND chooser for [text], started from the IME itself, so sharing
     * works in every editor. `performContextMenuAction(android.R.id.shareText)` is honoured only
     * by a stock EditText — Chrome/WebView and Compose ignore it
     * (docs/research/2026-10-15-ime-editor-actions.md §1.3, §1.4, §3.2). Only this path runs, so
     * an EditText never shares twice. A failure (no handler, a selection too large for the
     * binder) reaches [feedback] instead of crashing the IME.
     */
    fun share(context: Context, text: String, feedback: (String) -> Unit) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = TextActionPolicy.MIME_TEXT_PLAIN
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.cmd_share_text))
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            // PII: the shared text itself is never logged; only its length, in verbose builds.
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Launched share chooser (len=${text.length})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch the share chooser", e)
            feedback(context.getString(R.string.dynamic_action_unavailable))
        }
    }

    /**
     * Start [ProcessTextActivity], which opens the ACTION_PROCESS_TEXT chooser for [text] with
     * startActivityForResult and hands any returned text to [ProcessTextReturn] under
     * [requestId]. False when the activity cannot be started (the caller falls back).
     */
    fun launchForResult(context: Context, requestId: Long, text: String, action: TextActionPolicy.TextAction): Boolean =
        try {
            val intent = Intent(context, ProcessTextActivity::class.java).apply {
                putExtra(ProcessTextActivity.EXTRA_REQUEST_ID, requestId)
                putExtra(ProcessTextActivity.EXTRA_TEXT, text)
                putExtra(ProcessTextActivity.EXTRA_ACTION, action.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Launched $action for a result (len=${text.length})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start the process-text trampoline for $action", e)
            false
        }

    /**
     * The editor context-menu id tried when the chooser cannot be started, or null when no
     * editor action would do anything: `android.R.id.textAssist` is handled by no editor
     * (research 2026-10-15 §1 — why `textAssistMenu` was removed), so Text assist reports the
     * action as unavailable instead. `replaceText` does show suggestions on a word carrying them.
     */
    fun fallbackMenuId(action: TextActionPolicy.TextAction): Int? = when (action) {
        TextActionPolicy.TextAction.ASSIST -> null
        TextActionPolicy.TextAction.REPLACE -> android.R.id.replaceText
    }
}
