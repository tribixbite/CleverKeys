package tribixbite.keyboard2.prefs

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.DialogPreference
import kotlin.math.max
import kotlin.math.round

/**
 * SlideBarPreference
 * -
 * Open a dialog showing a seekbar
 * -
 * xml attrs:
 *   android:defaultValue  Default value (float)
 *   min                   min value (float)
 *   max                   max value (float)
 * -
 * Summary field allows showing the current value using %f or %s flag
 */
class SlideBarPreference(
    context: Context,
    attrs: AttributeSet?
) : DialogPreference(context, attrs), SeekBar.OnSeekBarChangeListener {

    private val min: Float
    private val max: Float
    private var value: Float
    private val initialSummary: CharSequence?

    init {
        initialSummary = summary
        min = floatOfString(attrs?.getAttributeValue(null, "min"))
        value = min
        max = max(1f, floatOfString(attrs?.getAttributeValue(null, "max")))

        // Set up dialog layout resource
        dialogLayoutResource = tribixbite.keyboard2.R.layout.preference_slider_dialog
    }

    // SeekBar for dialog
    private var seekBar: SeekBar? = null
    private var textView: TextView? = null

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        value = round(progress * (max - min)) / STEPS.toFloat() + min
        updateText()
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {}

    override fun onStopTrackingTouch(seekBar: SeekBar) {}

    /**
     * Safely get persisted float, handling legacy values from older settings exports.
     * Older versions may have stored Integer or String instead of Float.
     */
    private fun getSafePersistedFloat(defaultValue: Float): Float {
        return try {
            getPersistedFloat(defaultValue)
        } catch (e: ClassCastException) {
            // Try Integer first
            try {
                val intValue = getPersistedInt(defaultValue.toInt())
                val floatValue = intValue.toFloat()
                persistFloat(floatValue)
                floatValue
            } catch (e2: ClassCastException) {
                // Try String
                try {
                    val strValue = getPersistedString(defaultValue.toString())
                    val floatValue = strValue?.toFloat() ?: defaultValue
                    persistFloat(floatValue)
                    floatValue
                } catch (e3: Exception) {
                    // Give up and use default
                    persistFloat(defaultValue)
                    defaultValue
                }
            }
        }
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        val defVal = (defaultValue as? Float) ?: min
        value = getSafePersistedFloat(defVal)
        updateText()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getFloat(index, min)
    }

    fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            persistFloat(value)
        } else {
            value = getSafePersistedFloat(min)
        }
        updateText()
    }

    fun onBindDialogView(view: View) {
        textView = view.findViewById(tribixbite.keyboard2.R.id.slider_value)
        seekBar = view.findViewById<SeekBar>(tribixbite.keyboard2.R.id.slider_seekbar)?.apply {
            max = STEPS
            progress = ((value - min) * STEPS / (this@SlideBarPreference.max - min)).toInt()
            setOnSeekBarChangeListener(this@SlideBarPreference)
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

    fun getValue(): Float = value

    fun setValue(newValue: Float) {
        value = newValue
        seekBar?.progress = ((value - min) * STEPS / (max - min)).toInt()
        updateText()
    }

    companion object {
        private const val STEPS = 100

        private fun floatOfString(str: String?): Float {
            return str?.toFloatOrNull() ?: 0f
        }
    }
}
