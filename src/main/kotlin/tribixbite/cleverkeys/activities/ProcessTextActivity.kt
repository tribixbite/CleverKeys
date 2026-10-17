package tribixbite.cleverkeys

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline for textAssist / replaceText: starts the ACTION_PROCESS_TEXT app
 * chooser with startActivityForResult — an IME service cannot receive a result — and hands the
 * returned EXTRA_PROCESS_TEXT to [ProcessTextReturn], where the IME writes it back once the
 * original field has the focus again (`KeyEventHandler.deliverProcessTextResult`).
 *
 * The system chooser forwards the chosen app's result to the caller
 * (FLAG_ACTIVITY_FORWARD_RESULT on its targets; AOSP DisplayResolveInfo, and IntentResolver's
 * since Android 14). Not exported: only [TextActionLauncher] starts it, in its own task
 * (taskAffinity "", excluded from recents), so finishing returns to the app being typed in.
 * Never noHistory — that would finish it while the chooser is up and lose the result.
 */
class ProcessTextActivity : Activity() {

    private var requestId = 0L

    @SuppressLint("InlinedApi") // ACTION_PROCESS_TEXT & extras: API 23, below minSdk 24.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)
        // Recreated (rotation, process kept): the chooser is already up; wait for its result.
        if (savedInstanceState != null) return
        val text = intent.getStringExtra(EXTRA_TEXT)
        if (text.isNullOrEmpty() || !ProcessTextReturn.isPending(requestId)) {
            finish()
            return
        }
        val action = runCatching {
            TextActionPolicy.TextAction.valueOf(intent.getStringExtra(EXTRA_ACTION) ?: "")
        }.getOrDefault(TextActionPolicy.TextAction.REPLACE)
        // The request is described by the pure policy (pinned by ReleaseClaimTextActionsTest):
        // editable, so the chosen app may return replacement text.
        val request = TextActionPolicy.processTextRequest(text, action)
        val process = Intent(request.action).apply {
            type = request.mimeType
            putExtra(Intent.EXTRA_PROCESS_TEXT, request.text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, request.readOnly)
        }
        val title = getString(request.chooserTitleRes)
        try {
            // No FLAG_ACTIVITY_NEW_TASK here: a new task would deliver RESULT_CANCELED at once.
            startActivityForResult(Intent.createChooser(process, title), REQUEST_PROCESS_TEXT)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the process-text chooser", e)
            ProcessTextReturn.cancel(requestId)
            finish()
        }
    }

    @SuppressLint("InlinedApi")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROCESS_TEXT) return
        val result = if (resultCode == RESULT_OK) data?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() else null
        // PII: neither the selection nor the result is ever logged.
        if (result != null) ProcessTextReturn.complete(requestId, result) else ProcessTextReturn.cancel(requestId)
        finish()
    }

    companion object {
        private const val TAG = "ProcessTextActivity"
        private const val REQUEST_PROCESS_TEXT = 1
        const val EXTRA_REQUEST_ID = "tribixbite.cleverkeys.extra.PROCESS_TEXT_REQUEST"
        const val EXTRA_TEXT = "tribixbite.cleverkeys.extra.PROCESS_TEXT_TEXT"
        const val EXTRA_ACTION = "tribixbite.cleverkeys.extra.PROCESS_TEXT_ACTION"
    }
}
