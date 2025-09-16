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
 * SlideBarPreference - Kotlin conversion from Unexpected Keyboard
 * Opens a dialog showing a seekbar for float value selection
 *
 * XML attributes:
 * - android:defaultValue: Default value (float)
 * - min: Minimum value (float)
 * - max: Maximum value (float)
 *
 * Summary field supports showing current value using %f or %s flag
 */
class SlideBarPreference(
    context: Context,
    attrs: AttributeSet
) : DialogPreference(context, attrs), SeekBar.OnSeekBarChangeListener {

    companion object {
        private const val STEPS = 100
    }

    private val layout: LinearLayout
    private val textView: TextView
    private val seekBar: SeekBar
    private val min: Float
    private val max: Float
    private var value: Float
    private val initialSummary: String

    init {
        initialSummary = summary?.toString() ?: ""

        textView = TextView(context).apply {
            setPadding(48, 40, 48, 40)
        }

        seekBar = SeekBar(context).apply {
            setOnSeekBarChangeListener(this@SlideBarPreference)
            max = STEPS
        }

        min = parseFloatAttribute(attrs.getAttributeValue(null, "min"))
        max = parseFloatAttribute(attrs.getAttributeValue(null, "max"))
        value = min

        layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView)
            addView(seekBar)
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        value = min + (progress.toFloat() / STEPS) * (max - min)
        updateText()
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        // No action needed
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        // No action needed
    }

    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        value = if (restorePersistedValue) {
            getPersistedFloat(min)
        } else {
            parseFloatValue(defaultValue) ?: min
        }

        if (!restorePersistedValue) {
            persistFloat(value)
        }

        val progress = ((value - min) / (max - min) * STEPS).toInt()
        seekBar.progress = progress
        updateText()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getFloat(index, min)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            persistFloat(value)
        } else {
            val persistedValue = getPersistedFloat(min)
            val progress = ((persistedValue - min) / (max - min) * STEPS).toInt()
            seekBar.progress = progress
            value = persistedValue
        }
        updateText()
    }

    override fun onCreateDialogView(): View {
        val parent = layout.parent as? ViewGroup
        parent?.removeView(layout)
        return layout
    }

    private fun updateText() {
        val formattedValue = String.format(initialSummary, value)
        textView.text = formattedValue
        summary = formattedValue
    }

    private fun parseFloatAttribute(value: String?): Float {
        return try {
            value?.toFloat() ?: 0f
        } catch (e: NumberFormatException) {
            0f
        }
    }

    private fun parseFloatValue(value: Any?): Float? {
        return when (value) {
            is Float -> value
            is String -> try { value.toFloat() } catch (e: NumberFormatException) { null }
            is Number -> value.toFloat()
            else -> null
        }
    }
}