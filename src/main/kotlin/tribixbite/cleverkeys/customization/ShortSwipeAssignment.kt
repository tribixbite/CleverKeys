package tribixbite.cleverkeys.customization

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.a11y.ShortSwipeAccessibilityActions
import tribixbite.cleverkeys.shortSwipeActionDescription

/**
 * Save, remove and restore one (key, direction) slot. The single implementation behind both
 * places a slot is edited, so they cannot drift apart:
 * - `ShortSwipeCustomizationActivity` (Settings → Customize Per-Key Actions);
 * - `SubkeyAssignActivity` (the subkey popover's assign/edit screen).
 */
object ShortSwipeAssignment {

    /** Store the palette's [selection] for [keyCode]/[direction] and confirm it with a toast. */
    suspend fun apply(
        context: Context,
        manager: ShortSwipeCustomizationManager,
        keyCode: String,
        direction: SwipeDirection,
        selection: MappingSelection,
    ): ShortSwipeMapping {
        val mapping = ShortSwipeMapping(
            keyCode = keyCode,
            direction = direction,
            displayText = selection.displayLabel,  // User-customized label
            actionType = selection.actionType,
            actionValue = selection.actionValue,   // Actual action/command
            useKeyFont = selection.useKeyFont      // Use icon font if applicable
        )
        manager.setMapping(mapping)
        toast(context, mappedMessage(context, mapping))
        return mapping
    }

    /**
     * The confirmation after [mapping] was saved, e.g. `Mapped Northwest → "@" (Text: "@")` or
     * `Mapped Northwest → "Voice input (on device)" (Command: Voice input (on device))`.
     */
    fun mappedMessage(context: Context, mapping: ShortSwipeMapping): String = context.getString(
        R.string.short_swipe_toast_mapped,
        context.getString(mapping.direction.displayNameRes),
        textLabel(mapping, context::getString),
        shortSwipeActionDescription(context, mapping.actionType, mapping.actionValue)
    )

    /**
     * [mapping]'s label for text drawn in the SYSTEM font (toasts, lists): its on-key label when
     * that is plain text, else its spoken name. A key-font label is a private-use glyph that the
     * system font draws as an unrelated character — the microphone showed as a CJK ideograph in
     * the "Mapped" toast (2026-10-30, screenshot 9).
     */
    fun textLabel(mapping: ShortSwipeMapping, getString: (Int) -> String): String {
        val label = mapping.displayText
        val plain = !mapping.useKeyFont && label.isNotBlank() &&
            label.none { Character.isISOControl(it) || it.code in 0xE000..0xF8FF }
        return if (plain) label else ShortSwipeAccessibilityActions.describeMapping(mapping, getString)
    }

    /** The name of the key [keyCode] for a toast: "Enter", "Space", "A" (never a raw glyph). */
    private fun keyName(context: Context, keyCode: String): String =
        ShortSwipeKeyCodes.displayName(keyCode, null, context::getString)

    /**
     * Blank the slot. When the layout has a subkey there ([hasDefault]) a `removed` mapping
     * hides it (the only way to remove a default); otherwise the custom mapping is deleted.
     */
    suspend fun remove(
        context: Context,
        manager: ShortSwipeCustomizationManager,
        keyCode: String,
        direction: SwipeDirection,
        hasDefault: Boolean,
    ) {
        if (hasDefault) manager.setMapping(ShortSwipeMapping.removal(keyCode, direction))
        else manager.removeMapping(keyCode, direction)
        toast(context, context.getString(R.string.subkey_toast_removed, context.getString(direction.displayNameRes), keyName(context, keyCode)))
    }

    /** Drop the custom mapping so the layout's own subkey shows again. */
    suspend fun restoreDefault(
        context: Context,
        manager: ShortSwipeCustomizationManager,
        keyCode: String,
        direction: SwipeDirection,
    ) {
        manager.removeMapping(keyCode, direction)
        toast(context, context.getString(R.string.subkey_toast_restored, context.getString(direction.displayNameRes), keyName(context, keyCode)))
    }

    /**
     * Show [text] on the main thread. These functions suspend on the manager's file I/O, and the
     * caller's dispatcher decides where they resume: a Compose UI test's interceptor resumed one
     * on an I/O worker, where `Toast` throws "Can't toast on a thread that has not called
     * Looper.prepare()" (SubkeyAssignScreenSourcesComposeTest, emulator.wtf 2026-10-30).
     */
    private fun toast(context: Context, text: String) {
        val show = { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
        if (Looper.myLooper() == Looper.getMainLooper()) show() else Handler(Looper.getMainLooper()).post(show)
    }
}
