package tribixbite.cleverkeys.customization

import com.google.gson.Gson

/**
 * The mappings a Reset All removed, kept as text so the Undo offer survives a rotation
 * (`rememberSaveable` in ShortSwipeCustomizationActivity). Before, the removed list lived only in
 * the coroutine that showed the snackbar: rotating cancelled it, the snackbar vanished and the
 * mappings could no longer be restored (review 2026-11-14, low).
 *
 * Encoded with the storage model ([ShortSwipeCustomizations]) the manager writes to disk, so a
 * restored mapping is exactly what a reload would give. Pure; no Android or Compose classes.
 */
object ShortSwipeResetUndo {

    /**
     * Saved instance state shares the ~1 MB binder transaction with everything else the activity
     * saves. An encoding longer than this (about 200 mappings with long intents) is not kept
     * across a rotation, so saving can never crash the activity; Undo still works until then.
     */
    const val MAX_SAVED_CHARS = 100_000

    private val gson = Gson()

    fun encode(removed: List<ShortSwipeMapping>): String =
        gson.toJson(ShortSwipeCustomizations.fromMappingList(removed))

    /** The mappings in [encoded], or an empty list when it cannot be read. */
    fun decode(encoded: String): List<ShortSwipeMapping> = try {
        gson.fromJson(encoded, ShortSwipeCustomizations::class.java)?.toMappingList().orEmpty()
    } catch (e: RuntimeException) {
        emptyList()
    }

    /** [encoded] when it is small enough to go into saved instance state, else null. */
    fun saveable(encoded: String?): String? = encoded?.takeIf { it.length <= MAX_SAVED_CHARS }
}
