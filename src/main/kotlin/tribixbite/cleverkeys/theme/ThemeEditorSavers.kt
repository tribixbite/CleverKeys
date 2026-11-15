package tribixbite.cleverkeys.theme

import androidx.compose.runtime.saveable.Saver
import org.json.JSONObject

/**
 * Savers that carry Theme Manager's in-progress edits through the saved-state Bundle (review
 * 2026-11-14 M22): none of the theme screens declares `configChanges`, so a rotation recreated the
 * activity and plain `remember` state — the Theme Creator's draft, the theme being edited — was
 * gone. Each saver stores a JSON string written by the codec the theme store already uses, so a
 * draft survives exactly as a saved theme would. Pinned by `ThemeEditorSaversTest`.
 */
object ThemeEditorSavers {

    /** The draft's 17 DIY colours. */
    val ColorScheme: Saver<KeyboardColorScheme, String> = Saver(
        save = { it.toJson().toString() },
        restore = { KeyboardColorSchemeHelper.fromJson(JSONObject(it)) },
    )

    /** The draft's "Keyboard look" part (an empty spec while nothing is set). */
    val Spec: Saver<ThemeSpec, String> = Saver(
        save = { ThemeSpecJson.toJson(it).toString() },
        restore = { ThemeSpecJson.fromJson(JSONObject(it)) },
    )

    /**
     * The custom theme an Edit dialog is open for; nothing is saved while no dialog is open (a
     * null save restores the initial value, null). Its id and creation time come back too, so
     * Save still replaces the same store entry.
     */
    val EditedTheme: Saver<CustomTheme?, String> = Saver(
        save = { theme -> theme?.toJson()?.toString() },
        restore = { CustomTheme.fromJson(JSONObject(it)) },
    )
}
