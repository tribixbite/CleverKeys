package tribixbite.cleverkeys.ml

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.util.Log

/**
 * Swipe Playground recording + live-panel bridge (2026-09-03).
 *
 * Fires ONLY while the IME's debug mode is on — i.e. while `SwipeDebugActivity` (the
 * playground) is VISIBLE: the activity enables debug mode in `onStart` and disables it
 * in `onStop` (I-1, comprehensive audit 2026-09-06 — the old onCreate/onDestroy binding
 * left recording on while the activity was backgrounded). That makes playground
 * recording an EXPLICIT user session, the same category as the removed
 * SwipeCalibrationActivity: the user opened a screen whose stated purpose is recording
 * swipes, so it deliberately does not consult `LearningGate.canCollectSwipeMl` (which
 * governs AUTOMATIC background collection — see the "out of scope" list in
 * [tribixbite.cleverkeys.LearningGate]'s KDoc). The playground UI discloses that
 * recorded traces contain typed content.
 *
 * Second fence (I-1): the session boundary above is a lifecycle promise, and lifecycle
 * promises can go stale (a raced broadcast, a spoofed one — I-8). So the recorder ALSO
 * verifies the BOUND EDITOR belongs to this app before persisting or broadcasting
 * anything — see [recordAndBroadcast]. A stale debug flag can therefore never capture
 * typing done in another app.
 *
 * Duplicate-avoidance: when the swipe was ALREADY persisted by the gated global path
 * (`MLDataCollector`, source `"user_selection"`), no second row is written — the global
 * row carries the same enrichment (candidates/geometry/latency are attached to the
 * capture before either store runs), and a per-swipe double row would inflate any
 * later corpus. `storedAs` in the payload tells the panel which store took it.
 */
object PlaygroundTraceRecorder {

    private const val TAG = "PlaygroundTraceRecorder"

    /** Collection source for rows persisted by the playground itself. */
    const val SOURCE_PLAYGROUND = "playground"

    /** Package-scoped broadcast carrying one decoded swipe's [PlaygroundPayload]. */
    const val ACTION_SWIPE_RESULT = "tribixbite.cleverkeys.SWIPE_PLAYGROUND_RESULT"
    const val EXTRA_PAYLOAD = "payload"

    /**
     * Persist the enriched trace (unless the global collection already did) and
     * broadcast the live-panel payload to the playground activity.
     *
     * @param swipeData the capture-time trace, already enriched with candidates,
     *   key geometry and latency by the caller; null when no capture existed
     * @param committedWord the word actually committed (post-autocorrect)
     * @param engineWordCount leading entries of the ranking that came from the decoder
     * @param storedGlobally true when `MLDataCollector` already persisted this swipe
     *   (source `"user_selection"`) under its own privacy gates
     */
    fun recordAndBroadcast(
        context: Context,
        swipeData: SwipeMLData?,
        committedWord: String,
        engineWordCount: Int,
        storedGlobally: Boolean
    ) {
        // I-1: fail closed on foreign editors. The production caller passes the
        // CleverKeysService itself, so the bound editor's package is known; recording
        // requires POSITIVE confirmation that the editor belongs to this app (the
        // playground's own test field). A stale/spoofed debug flag must never persist —
        // or even broadcast — words the user typed into another app.
        val editorPackage = (context as? InputMethodService)?.currentInputEditorInfo?.packageName
        if (editorPackage != context.packageName) {
            Log.w(TAG, "Playground recording skipped: bound editor is not this app")
            return
        }

        var storedAs = if (storedGlobally) PlaygroundPayload.STORED_GLOBAL else PlaygroundPayload.STORED_NONE
        if (!storedGlobally && swipeData != null) {
            try {
                // The capture-time object has an empty target word; the committed word is
                // only known now. copyWith carries points/keys/provenance/enrichment over.
                val row = swipeData.copyWith(committedWord, SOURCE_PLAYGROUND)
                if (row.isValid()) {
                    SwipeMLDataStore.getInstance(context).storeSwipeData(row)
                    storedAs = PlaygroundPayload.STORED_PLAYGROUND
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist playground trace", e)
            }
        }

        try {
            val payload = PlaygroundPayload.build(swipeData, committedWord, engineWordCount, storedAs)
            val intent = Intent(ACTION_SWIPE_RESULT).apply {
                setPackage(context.packageName) // app-internal broadcast only
                putExtra(EXTRA_PAYLOAD, payload.toString())
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast playground payload", e)
        }
    }
}
