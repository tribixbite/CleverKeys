package tribixbite.cleverkeys.clipboard

import java.nio.charset.StandardCharsets

/**
 * #156 Private copy — pure-JVM validation for the PROCESS_TEXT selection-toolbar entry point.
 *
 * SECURITY DESIGN (design §6.2 row 5): this parser is the ONLY intent-reading code for
 * [tribixbite.cleverkeys.PrivateCopyProcessTextActivity], and its API deliberately accepts ONLY
 * primitives + the single [CharSequence] text extra. It has no `Intent` parameter, so it CANNOT
 * receive `clipData`, `EXTRA_STREAM`, a URI, or any other extra — structurally closing the classic
 * "exported activity coerced into opening attacker URIs" class. The hostile-extra matrix is moot by
 * construction; the test asserts the API shape via compilation.
 *
 * No Android dependencies — unit-tested by PrivateCopyIntentParserTest.
 */
object PrivateCopyIntentParser {

    /** Android's `Intent.ACTION_PROCESS_TEXT`, hard-coded so this stays Android-free. */
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"

    /** Outcome of validating a PROCESS_TEXT launch. */
    sealed class Result {
        /** Accept: [text] is the trimmed, size-checked payload to store privately. */
        data class Accept(val text: String) : Result()
        /** Reject with a machine-readable [reason] (logging only; no UI channel to the caller). */
        data class Reject(val reason: Reason) : Result()
    }

    /** Why a launch was rejected. */
    enum class Reason {
        WRONG_ACTION,   // action is not ACTION_PROCESS_TEXT
        NO_EXTRA,       // EXTRA_PROCESS_TEXT absent (text == null)
        BLANK,          // text is empty or whitespace-only after trim
        OVER_CAP,       // UTF-8 byte length exceeds the per-item cap
    }

    /**
     * Validate a PROCESS_TEXT launch.
     *
     * @param action      the intent action (e.g. `intent.action`).
     * @param text        the value of `EXTRA_PROCESS_TEXT` (or `EXTRA_PROCESS_TEXT_READONLY`'s
     *                    associated text); null when the extra is absent. READONLY is irrelevant to
     *                    behavior (design §6.4) — the caller reads it for logging only, not here.
     * @param maxBytes    per-item UTF-8 byte cap (the same `clipboard_max_item_size_kb`-class limit
     *                    [tribixbite.cleverkeys.ClipboardHistoryService] enforces). `<= 0` disables
     *                    the cap check (mirrors the service's `maxSizeKb > 0` guard).
     */
    fun parse(action: String?, text: CharSequence?, maxBytes: Int): Result {
        if (action != ACTION_PROCESS_TEXT) return Result.Reject(Reason.WRONG_ACTION)
        if (text == null) return Result.Reject(Reason.NO_EXTRA)
        val str = text.toString()
        val trimmed = str.trim()
        if (trimmed.isEmpty()) return Result.Reject(Reason.BLANK)
        if (maxBytes > 0) {
            val bytes = trimmed.toByteArray(StandardCharsets.UTF_8).size
            if (bytes > maxBytes) return Result.Reject(Reason.OVER_CAP)
        }
        return Result.Accept(trimmed)
    }
}
