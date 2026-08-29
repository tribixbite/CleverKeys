package tribixbite.cleverkeys.swipe

/**
 * ARC-083 — how a CTC decode ATTEMPT ends, decided in one pure place.
 *
 * `CtcEngineAdapter.decodeAsync` runs the whole decode (featurize → ONNX `emit` → beam →
 * overlays) inside a task on its own thread. Three things can come out of that task, and until
 * 2026-08-29 only two of them were distinguished:
 *
 *  - **[Disposition.DELIVER]** — a slate. Goes to the bar.
 *  - **[Disposition.DROP]** — the swipe was SUPERSEDED. `PredictionTaskRunner.cancelAndSubmit`
 *    cancels the previous decode with an interrupt, which surfaces either as an
 *    [InterruptedException] or as some other exception thrown by whatever the interrupt landed
 *    in. The user has already started a newer swipe; the right answer is silence — and
 *    explicitly NOT a geometric re-decode of a gesture the user replaced, which would race the
 *    newer decode for the bar.
 *  - **[Disposition.FALL_BACK]** — a genuine, TRANSIENT decode failure: an ORT fault inside
 *    `session.run`, a decode racing a layout/trie swap, an encoder load that failed for the
 *    first time. This one used to be answered with an empty slate, which the shared pipeline
 *    renders exactly like "no candidates": the bar cleared for that swipe with nothing logged
 *    at the point of failure and nothing for the user to act on — while a working geometric
 *    decoder sat idle. It now goes back to the dispatcher, which hands the swipe to
 *    `InputCoordinator.performGeometricSwipeTyping`, the same remedy the language, layout and
 *    dead-session gates already use.
 *
 * ## What this object deliberately does NOT do
 *
 * It does not latch. A transient failure leaves CTC fully eligible for the next swipe: the only
 * latch in the engine is `CtcEngineAdapter.modelOrNull`'s per-ASSET one, which fires solely
 * after `MAX_MODEL_LOAD_ATTEMPTS` consecutive session-load failures and is consulted by the
 * dispatcher BEFORE a swipe is routed here. Two failure classes, two lifetimes: permanent
 * failures are answered once, before dispatch; transient ones are answered per swipe, here.
 *
 * It also does not know what the fallback IS. The adapter cannot reach the geometric engine and
 * must not try to — it reports the failure and the dispatcher decides, so the "one engine owns
 * each swipe end-to-end" rule in [SwipeEngineRouter]'s KDoc still holds.
 *
 * Pure (no Android, no ONNX) so the seam is unit-testable — see `CtcDecodeDeliveryTest`.
 */
object CtcDecodeDelivery {

    /** What the adapter must do with a finished decode attempt. */
    enum class Disposition {
        /** The decode produced a slate — post it. */
        DELIVER,

        /** Superseded by a newer swipe — post nothing, and do NOT fall back. */
        DROP,

        /** Transient failure — hand this swipe to the geometric engine. */
        FALL_BACK,
    }

    /**
     * Thrown by the decode body when its INPUTS could not be assembled — no `CtcLayout` for the
     * board, no lexicon for the language, no ONNX session.
     *
     * The dispatcher pre-checks all three (`supportsLayout` / `hasLexiconSource` /
     * `isModelPermanentlyUnavailable`), so reaching this means something changed underneath the
     * swipe or a load failed for the first time — i.e. exactly the transient class. Raising it
     * rather than returning an empty result is what routes those cases to [Disposition.FALL_BACK]
     * alongside the thrown ones; [what] names the missing input for the log.
     */
    class DecodeInputsUnavailable(what: String) :
        Exception("CTC decode inputs unavailable: $what")

    /**
     * Classifies a finished attempt. [error] is null for a clean decode; [threadInterrupted] is
     * the decode thread's interrupt flag, read AFTER the catch.
     *
     * The interrupt flag is consulted because cancellation does not always surface as an
     * [InterruptedException]: an interrupt landing inside a native ORT call or a non-blocking
     * loop is observed as whatever that code throws, and treating that as a decode failure would
     * fire a geometric re-decode for a swipe the user already replaced.
     */
    fun dispositionOf(error: Throwable?, threadInterrupted: Boolean): Disposition = when {
        error == null -> Disposition.DELIVER
        threadInterrupted || isCancellation(error) -> Disposition.DROP
        else -> Disposition.FALL_BACK
    }

    /** True when [error] is, or wraps, an [InterruptedException]. */
    private fun isCancellation(error: Throwable): Boolean {
        var t: Throwable? = error
        // Bounded walk: a self-referential or cyclic cause chain must not hang the decode thread.
        var hops = 0
        while (t != null && hops++ < MAX_CAUSE_HOPS) {
            if (t is InterruptedException) return true
            val next = t.cause
            if (next === t) return false
            t = next
        }
        return false
    }

    private const val MAX_CAUSE_HOPS = 8

    /**
     * Runs [decode] and routes its outcome to exactly one of [onSuccess], [onCancelled] or
     * [onFallback].
     *
     * [onSuccess] is invoked OUTSIDE the guarded region on purpose: delivery posts to the main
     * thread and runs the caller's own callback, and an exception from THAT is not a decode
     * failure — converting it into one would re-decode the same swipe geometrically on top of a
     * slate that was already delivered. It propagates, exactly as it did before this seam existed.
     *
     * Catches [Exception], not [Throwable]: an [Error] (OOM, `StackOverflowError`) still
     * propagates to the executor untouched, because handing such a swipe to the geometric
     * engine — which allocates a template index — is not a recovery.
     *
     * @param interrupted reads the decode thread's interrupt flag; injectable so the
     *   cancellation rule is testable without actually interrupting a thread. Non-clearing by
     *   default — consuming the flag here would hide the cancellation from anything downstream.
     */
    fun <T> deliver(
        decode: () -> T,
        onSuccess: (T) -> Unit,
        onFallback: (Throwable) -> Unit,
        onCancelled: (Throwable) -> Unit = {},
        interrupted: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) {
        val value: T
        try {
            value = decode()
        } catch (e: Exception) {
            when (dispositionOf(e, interrupted())) {
                Disposition.DROP -> onCancelled(e)
                // DELIVER is unreachable for a non-null error; treat anything that is not a
                // cancellation as the transient failure it is.
                else -> onFallback(e)
            }
            return
        }
        onSuccess(value)
    }
}
