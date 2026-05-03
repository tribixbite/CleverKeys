package tribixbite.cleverkeys.backup

/**
 * Typed preference value used by the import-plan + apply pipeline.
 *
 * Replaces the lossy `String` approach the legacy `importConfig` path used —
 * splitting build (parse + validate + coerce) from apply (pure dispatch)
 * means the apply loop must know the EXACT type to call `editor.put*` with.
 *
 * `LongV` and `StrSet` are deliberately omitted — Config.kt has no `getLong`
 * usage and `isStringSetPreference` always returns `false` for live prefs.
 * If a future pref needs them, add them here and to the apply-loop `when`.
 */
sealed class PrefValue {
    data class Bool(val v: Boolean) : PrefValue()
    data class IntV(val v: Int) : PrefValue()
    data class FloatV(val v: Float) : PrefValue()
    data class Str(val v: String) : PrefValue()
    /** Canonicalized JSON string for layouts / extra_keys / custom_extra_keys. */
    data class JsonBlob(val raw: String) : PrefValue()
    /** The key is absent from current prefs (used for ChangeType.ADDED). */
    object Unset : PrefValue()
}

/**
 * Screen size + density needed by `migrateLegacyMargins` (dp→px) and the
 * Source vs Current screen-mismatch warning. Passing this to the pure
 * builder lets us avoid `context.resources.displayMetrics`.
 */
data class ScreenMetrics(
    val width: Int,
    val height: Int,
    val density: Float,
)
