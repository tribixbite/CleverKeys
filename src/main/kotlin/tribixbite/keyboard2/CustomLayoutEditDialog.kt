package tribixbite.keyboard2

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.text.InputType
import android.widget.EditText
import kotlin.math.log10

object CustomLayoutEditDialog {
    /**
     * Dialog for specifying a custom layout. [initialText] is the layout
     * description when modifying a layout.
     */
    @JvmStatic
    fun show(
        ctx: Context,
        initialText: String,
        allowRemove: Boolean,
        callback: Callback
    ) {
        val input = LayoutEntryEditText(ctx).apply {
            setText(initialText)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setView(input)
            .setTitle(R.string.pref_custom_layout_title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                callback.select(input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)

        // Might be true when modifying an existing layout
        if (allowRemove) {
            dialog.setNeutralButton(R.string.pref_layouts_remove_custom) { _, _ ->
                callback.select(null)
            }
        }

        input.set_on_text_change(object : LayoutEntryEditText.OnChangeListener {
            override fun on_change() {
                val error = callback.validate(input.text.toString())
                input.error = error
            }
        })

        dialog.show()
    }

    interface Callback {
        /**
         * The entered text when the user clicks "OK", [null] when the user
         * cancels editing.
         */
        fun select(text: String?)

        /**
         * Return a human readable error string if the [text] contains an error.
         * Return [null] otherwise. The error string will be displayed atop the
         * input box. This method is called everytime the text changes.
         */
        fun validate(text: String): String?
    }

    /** An editable text view that shows line numbers. */
    class LayoutEntryEditText(ctx: Context) : EditText(ctx) {
        /** Used to draw line numbers. */
        private val lnPaint: Paint = Paint(paint).apply {
            textSize = textSize * 0.8f
        }

        private var onChangeListener: OnChangeListener? = null

        /** Delay validation to when user stops typing for a second. */
        private val onChangeThrottler: Handler = Handler(ctx.mainLooper)
        private val onChangeDelayed = Runnable {
            onChangeListener?.on_change()
        }

        init {
            setHorizontallyScrolling(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        fun set_on_text_change(l: OnChangeListener?) {
            onChangeListener = l
        }

        override fun onDraw(canvas: Canvas) {
            val digitWidth = lnPaint.measureText("0")
            val lineCount = lineCount
            // Extra '+ 1' serves as padding.
            val padding = ((log10(lineCount.toDouble()).toInt() + 1 + 1) * digitWidth).toInt()
            setPadding(padding, 0, 0, 0)

            super.onDraw(canvas)

            lnPaint.color = paint.color
            val clipBounds = canvas.clipBounds
            val layout = layout
            val offset = clipBounds.left + (digitWidth / 2f).toInt()
            var line = layout.getLineForVertical(clipBounds.top)

            while (line < lineCount) {
                val baseline = getLineBounds(line, null)
                canvas.drawText(line.toString(), offset.toFloat(), baseline.toFloat(), lnPaint)
                line++
                if (baseline >= clipBounds.bottom) {
                    break
                }
            }
        }

        override fun onTextChanged(
            text: CharSequence,
            start: Int,
            lengthBefore: Int,
            lengthAfter: Int
        ) {
            onChangeThrottler.removeCallbacks(onChangeDelayed)
            onChangeThrottler.postDelayed(onChangeDelayed, 1000)
        }

        fun interface OnChangeListener {
            fun on_change()
        }
    }
}
