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
 * IntSlideBarPreference
 * -
 * Open a dialog showing a seekbar
 * -
 * xml attrs:
 *   android:defaultValue  Default value (int)
 *   min                   min value (int)
 *   max                   max value (int)
 * -
 * Summary field allow to show the current value using %s flag
 */
class IntSlideBarPreference(
    context: Context,
    attrs: AttributeSet?
) : DialogPreference(context, attrs), SeekBar.OnSeekBarChangeListener {

    private val layout: LinearLayout
    private val textView: TextView
    private val seekBar: SeekBar
    private val min: Int
    private val initialSummary: String

    init {
        initialSummary = summary.toString()
        textView = TextView(context).apply {
            setPadding(48, 40, 48, 40)
        }
        min = attrs?.getAttributeIntValue(null, "min", 0) ?: 0
        val max = attrs?.getAttributeIntValue(null, "max", 0) ?: 0

        seekBar = SeekBar(context).apply {
            setMax(max - min)
            setOnSeekBarChangeListener(this@IntSlideBarPreference)
        }

        layout = LinearLayout(getContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView)
            addView(seekBar)
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        updateText()
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {}

    override fun onStopTrackingTouch(seekBar: SeekBar) {}

    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        val value = if (restorePersistedValue) {
            getPersistedInt(min)
        } else {
            (defaultValue as Int).also { persistInt(it) }
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
        val f = String.format(initialSummary, seekBar.progress + min)
        textView.text = f
        summary = f
    }
}
