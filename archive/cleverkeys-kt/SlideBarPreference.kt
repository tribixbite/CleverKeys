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
import kotlin.math.max
import kotlin.math.round

/**
 * SideBarPreference
 * -
 * Open a dialog showing a seekbar
 * -
 * xml attrs:
 *   android:defaultValue  Default value (float)
 *   min                   min value (float)
 *   max                   max value (float)
 * -
 * Summary field allow to show the current value using %f or %s flag
 */
class SlideBarPreference(
    context: Context,
    attrs: AttributeSet?
) : DialogPreference(context, attrs), SeekBar.OnSeekBarChangeListener {

    private val layout: LinearLayout
    private val textView: TextView
    private val seekBar: SeekBar
    private val min: Float
    private val max: Float
    private var value: Float
    private val initialSummary: String

    init {
        initialSummary = summary.toString()
        textView = TextView(context).apply {
            setPadding(48, 40, 48, 40)
        }

        min = floatOfString(attrs?.getAttributeValue(null, "min"))
        value = min
        max = max(1f, floatOfString(attrs?.getAttributeValue(null, "max")))

        seekBar = SeekBar(context).apply {
            setMax(STEPS)
            setOnSeekBarChangeListener(this@SlideBarPreference)
        }

        layout = LinearLayout(getContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView)
            addView(seekBar)
        }
    }

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
                    val floatValue = strValue.toFloat()
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

    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        value = if (restorePersistedValue) {
            getSafePersistedFloat(min)
        } else {
            (defaultValue as Float).also { persistFloat(it) }
        }
        seekBar.progress = ((value - min) * STEPS / (max - min)).toInt()
        updateText()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getFloat(index, min)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            persistFloat(value)
        } else {
            seekBar.progress = ((getSafePersistedFloat(min) - min) * STEPS / (max - min)).toInt()
        }
        updateText()
    }

    override fun onCreateDialogView(): View {
        val parent = layout.parent as? ViewGroup
        parent?.removeView(layout)
        return layout
    }

    private fun updateText() {
        val f = String.format(initialSummary, value)
        textView.text = f
        summary = f
    }

    companion object {
        private const val STEPS = 100

        private fun floatOfString(str: String?): Float {
            return str?.toFloatOrNull() ?: 0f
        }
    }
}
