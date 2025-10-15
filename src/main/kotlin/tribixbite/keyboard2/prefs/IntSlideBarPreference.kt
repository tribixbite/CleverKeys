package tribixbite.keyboard2.prefs

import android.content.Context
import android.content.res.TypedArray
import android.preference.DialogPreference
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar

/**
 * IntSlideBarPreference - Slider preference for integer values
 * Opens a dialog showing a seekbar for integer value selection
 *
 * XML attributes:
 * - android:defaultValue: Default value (int)
 * - min: Minimum value (int)
 * - max: Maximum value (int)
 *
 * Summary field supports showing current value using %s flag
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
        initialSummary = summary?.toString() ?: ""

        textView = TextView(context).apply {
            setPadding(48, 40, 48, 40)
        }

        seekBar = SeekBar(context).apply {
            setOnSeekBarChangeListener(this@IntSlideBarPreference)
        }

        min = attrs.getAttributeIntValue(null, "min", 0)
        val max = attrs.getAttributeIntValue(null, "max", 0)
        seekBar.max = max - min

        layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView)
            addView(seekBar)
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        updateText()
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        // No action needed
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        // No action needed
    }

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

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, min)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            persistInt(seekBar.progress + min)
        } else {
            seekBar.progress = getPersistedInt(min) - min
        }
        updateText()
    }

    override fun onCreateDialogView(): View {
        val parent = layout.parent as? ViewGroup
        parent?.removeView(layout)
        return layout
    }

    private fun updateText() {
        // Bug #142 fix: Handle summary with or without format specifier
        val currentValue = seekBar.progress + min
        val formattedValue = try {
            String.format(initialSummary, currentValue)
        } catch (e: java.util.IllegalFormatException) {
            // No format specifier in summary - just append value
            if (initialSummary.isNotEmpty()) {
                "$initialSummary: $currentValue"
            } else {
                currentValue.toString()
            }
        }
        textView.text = formattedValue
        summary = formattedValue
    }
}
