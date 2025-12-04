package tribixbite.cleverkeys.prefs

import android.content.Context
import android.content.res.TypedArray
import android.preference.DialogPreference
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Integer Slider Dialog Preference
 *
 * Opens a dialog showing a seekbar for selecting integer values.
 * The summary field displays the current value using %s placeholder formatting.
 *
 * XML Attributes:
 * - `android:defaultValue`: Default value (int)
 * - `min`: Minimum value (int, default: 0)
 * - `max`: Maximum value (int, default: 0)
 *
 * Example XML:
 * ```xml
 * <IntSlideBarPreference
 *     android:key="swipe_threshold"
 *     android:title="Swipe Threshold"
 *     android:summary="Current value: %s pixels"
 *     android:defaultValue="10"
 *     min="5"
 *     max="50" />
 * ```
 *
 * Usage:
 * - The seekbar range is `min` to `max` inclusive
 * - Summary text can include "%s" which will be replaced with the current value
 * - Value is persisted to SharedPreferences automatically
 *
 * Ported from Java to Kotlin with improvements.
 */
class IntSlideBarPreference(
    context: Context,
    attrs: AttributeSet
) : DialogPreference(context, attrs), SeekBar.OnSeekBarChangeListener {

    private val layout: LinearLayout
    private val textView: TextView
    private val seekBar: SeekBar

    private val min: Int
    private val initialSummary: String

    init {
        // Store initial summary template (may contain %s placeholder)
        initialSummary = summary?.toString() ?: ""

        // Create text view for displaying current value
        textView = TextView(context).apply {
            // Convert dp to pixels for proper scaling across screen densities
            val paddingHorizontal = (48 * context.resources.displayMetrics.density).toInt()
            val paddingVertical = (40 * context.resources.displayMetrics.density).toInt()
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
        }

        // Get min/max from XML attributes
        min = attrs.getAttributeIntValue(null, "min", 0)
        val max = attrs.getAttributeIntValue(null, "max", 0)

        // Create seekbar with adjusted range (0 to max-min)
        seekBar = SeekBar(context).apply {
            setMax(max - min)
            setOnSeekBarChangeListener(this@IntSlideBarPreference)
        }

        // Create layout containing text view and seekbar
        layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView)
            addView(seekBar)
        }
    }

    /**
     * Called when seekbar progress changes
     */
    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        updateText()
    }

    /**
     * Called when user starts dragging the seekbar
     */
    override fun onStartTrackingTouch(seekBar: SeekBar) {
        // Empty implementation - not needed
    }

    /**
     * Called when user stops dragging the seekbar
     */
    override fun onStopTrackingTouch(seekBar: SeekBar) {
        // Empty implementation - not needed
    }

    /**
     * Set initial value from SharedPreferences or default
     */
    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        val value = if (restorePersistedValue) {
            getPersistedInt(min)
        } else {
            (defaultValue as? Int) ?: min
        }

        if (!restorePersistedValue) {
            persistInt(value)
        }

        seekBar.progress = value - min
        updateText()
    }

    /**
     * Get default value from XML attributes
     */
    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, min)
    }

    /**
     * Called when dialog is closed
     */
    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            // Save the new value
            persistInt(seekBar.progress + min)
        } else {
            // Restore the old value
            seekBar.progress = getPersistedInt(min) - min
        }

        updateText()
    }

    /**
     * Create the dialog view
     */
    override fun onCreateDialogView(): View {
        // Remove from parent if already attached
        (layout.parent as? ViewGroup)?.removeView(layout)
        return layout
    }

    /**
     * Update the text view and summary with current value
     */
    private fun updateText() {
        val currentValue = seekBar.progress + min
        val formattedText = String.format(initialSummary, currentValue)

        textView.text = formattedText
        summary = formattedText
    }
}
