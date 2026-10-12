package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.inputmethod.InputConnection

/**
 * Launches the selection-driven text actions (text assist, replace text) — the ONE
 * implementation behind the `textAssist`/`replaceText` editing keys, whether pressed on a
 * layout or fired by a custom mapping (`KeyEventHandler.handleEditingKey`, todo 12).
 *
 * Most editors do not implement `android.R.id.textAssist`/`replaceText`, so the action is an
 * `ACTION_PROCESS_TEXT` app chooser ([TextActionPolicy.processTextRequest]); the bare
 * context-menu id is only the fallback when the chooser cannot start.
 */
internal object TextActionLauncher {

    private const val TAG = "TextActionLauncher"

    /**
     * Run [action] on the selection of [ic]. With nothing selected, [feedback] receives the
     * localized "no text selected" message (shown in the suggestion bar; Toasts are suppressed
     * for IMEs on Android 13+).
     */
    // Intent.ACTION_PROCESS_TEXT / EXTRA_PROCESS_TEXT* (API 23) and android.R.id.textAssist
    // (API 26) / replaceText (API 23) are compile-time-inlined constants. On older APIs the
    // process-text intent resolves to no handler (caught) and performContextMenuAction() is a
    // safe no-op, so this degrades gracefully rather than crashing.
    @SuppressLint("InlinedApi")
    fun launch(
        context: Context,
        ic: InputConnection,
        action: TextActionPolicy.TextAction,
        feedback: (String) -> Unit,
    ) {
        val selectedText = ic.getSelectedText(0)?.toString()
        if (selectedText.isNullOrEmpty()) {
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "No text selected for $action")
            feedback(TextActionPolicy.noTextSelectedMessage(action) { id, args -> context.getString(id, *args) })
            return
        }
        try {
            val request = TextActionPolicy.processTextRequest(selectedText, action)
            val intent = Intent(request.action).apply {
                type = request.mimeType
                putExtra(Intent.EXTRA_PROCESS_TEXT, request.text)
                // readOnly = false — the chosen app may REPLACE the selection.
                putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, request.readOnly)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, context.getString(request.chooserTitleRes))
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            // PII: the selected text itself is never logged; only its length, in verbose builds.
            if (BuildConfig.ENABLE_VERBOSE_LOGGING) Log.d(TAG, "Launched $action chooser (len=${selectedText.length})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $action chooser", e)
            ic.performContextMenuAction(fallbackMenuId(action))
        }
    }

    /** The editor context-menu id tried when the chooser cannot be started. */
    @SuppressLint("InlinedApi")
    fun fallbackMenuId(action: TextActionPolicy.TextAction): Int = when (action) {
        TextActionPolicy.TextAction.ASSIST -> android.R.id.textAssist
        TextActionPolicy.TextAction.REPLACE -> android.R.id.replaceText
    }
}
