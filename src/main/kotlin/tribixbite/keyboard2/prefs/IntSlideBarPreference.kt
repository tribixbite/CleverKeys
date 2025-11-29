package tribixbite.keyboard2.prefs

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.DialogPreference

/**
 * IntSlideBarPreference
 * -
 * Open a dialog showing a seekbar for integer values
 * -
 * xml attrs:
 *   android:defaultValue  Default value (int)
 *   min                   min value (int)
 *   max                   max value (int)
 * -
 * Summary field allows showing the current value using %s flag
 */
class IntSlideBarPreference(
    context: Context,
    attrs: AttributeSet?
) : DialogPreference(context, attrs), SeekBar.OnSeekBarChangeListener {

    private val min: Int
    private val max: Int
    private var value: Int
    private val initialSummary: CharSequence?

    // Dialog views
    private var seekBar: SeekBar? = null
    private var textView: TextView? = null

    init {
        initialSummary = summary
        min = attrs?.getAttributeIntValue(null, "min", 0) ?: 0
        max = attrs?.getAttributeIntValue(null, "max", 100) ?: 100
        value = min

        // Set up dialog layout resource (shared with SlideBarPreference)
        dialogLayoutResource = tribixbite.keyboard2.R.layout.preference_slider_dialog
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        value = progress + min
        updateText()
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {}

    override fun onStopTrackingTouch(seekBar: SeekBar) {}

    override fun onSetInitialValue(defaultValue: Any?) {
        val defVal = (defaultValue as? Int) ?: min
        value = getPersistedInt(defVal)
        updateText()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, min)
    }

    fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            persistInt(value)
        } else {
            value = getPersistedInt(min)
        }
        updateText()
    }

    fun onBindDialogView(view: View) {
        textView = view.findViewById(tribixbite.keyboard2.R.id.slider_value)
        seekBar = view.findViewById<SeekBar>(tribixbite.keyboard2.R.id.slider_seekbar)?.apply {
            max = this@IntSlideBarPreference.max - min
            progress = value - min
            setOnSeekBarChangeListener(this@IntSlideBarPreference)
        }
        updateText()
    }

    private fun updateText() {
        val formattedValue = try {
            String.format(initialSummary?.toString() ?: "%s", value)
        } catch (e: Exception) {
            value.toString()
        }
        textView?.text = formattedValue
        summary = formattedValue
    }

    fun getValue(): Int = value

    fun setValue(newValue: Int) {
        value = newValue
        seekBar?.progress = value - min
        updateText()
    }
}
