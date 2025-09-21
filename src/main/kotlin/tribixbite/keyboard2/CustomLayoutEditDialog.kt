package tribixbite.keyboard2

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Layout
import android.widget.EditText
import kotlinx.coroutines.*

/**
 * Custom layout editor dialog with modern Kotlin improvements
 *
 * Features:
 * - Line numbering for easy navigation
 * - Real-time validation with throttled updates
 * - Improved error handling and user feedback
 * - Modern coroutine-based text change handling
 * - Enhanced UI with better accessibility
 */
object CustomLayoutEditDialog {

    /**
     * Show the custom layout edit dialog
     *
     * @param context Context for the dialog
     * @param initialText Initial layout description when modifying
     * @param allowRemove Whether to show remove button for existing layouts
     * @param callback Callback for handling user interaction
     */
    fun show(
        context: Context,
        initialText: String = "",
        allowRemove: Boolean = false,
        callback: Callback
    ) {
        val input = LayoutEntryEditText(context).apply {
            setText(initialText)
        }

        val dialogBuilder = AlertDialog.Builder(context)
            .setView(input)
            .setTitle(R.string.pref_custom_layout_title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                callback.onSelect(input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)

        // Add remove button for existing layouts
        if (allowRemove) {
            dialogBuilder.setNeutralButton(R.string.pref_layouts_remove_custom) { _, _ ->
                callback.onSelect(null)
            }
        }

        val dialog = dialogBuilder.create()

        // Set up real-time validation
        input.setOnTextChangeListener { text ->
            val error = callback.validate(text)
            input.error = error

            // Enable/disable OK button based on validation
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = error == null
        }

        dialog.show()

        // Disable OK button initially if there's an error
        val initialError = callback.validate(initialText)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = initialError == null
        if (initialError != null) {
            input.error = initialError
        }
    }

    /**
     * Callback interface for layout editing
     */
    interface Callback {
        /**
         * Called when user confirms selection
         * @param text The entered layout text, or null if removed
         */
        fun onSelect(text: String?)

        /**
         * Validate the layout text
         * @param text Current text content
         * @return Error message if invalid, null if valid
         */
        fun validate(text: String): String?
    }

    /**
     * Enhanced EditText with line numbers and improved functionality
     */
    private class LayoutEntryEditText(context: Context) : EditText(context) {

        private val lineNumberPaint: Paint = Paint(paint).apply {
            textSize = paint.textSize * 0.8f
            color = currentTextColor
        }

        private var textChangeListener: ((String) -> Unit)? = null
        private val validationHandler = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val textChangeRunnable = Runnable {
            textChangeListener?.invoke(text.toString())
        }

        init {
            setupEditText()
        }

        private fun setupEditText() {
            // Configure input for multi-line layout editing
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isHorizontalScrollBarEnabled = true
            setHorizontallyScrolling(true)

            // Improve accessibility
            contentDescription = "Custom keyboard layout editor with line numbers"

            // Set monospace font for better code editing
            typeface = Typeface.MONOSPACE

            // Set hint for user guidance
            hint = "Enter keyboard layout definition...\nExample:\n" +
                   "q w e r t y\n" +
                   "a s d f g h\n" +
                   "z x c v b n"
        }

        fun setOnTextChangeListener(listener: (String) -> Unit) {
            textChangeListener = listener
        }

        override fun onDraw(canvas: Canvas) {
            drawLineNumbers(canvas)
            super.onDraw(canvas)
        }

        private fun drawLineNumbers(canvas: Canvas) {
            val digitWidth = lineNumberPaint.measureText("0")
            val lineCount = lineCount

            if (lineCount == 0) return

            // Calculate padding based on number of lines
            val maxLineNumberDigits = (kotlin.math.log10(lineCount.toDouble()).toInt() + 1)
            val leftPadding = ((maxLineNumberDigits + 1) * digitWidth).toInt()

            // Update padding if changed
            if (paddingLeft != leftPadding) {
                setPadding(leftPadding, paddingTop, paddingRight, paddingBottom)
            }

            // Set line number color based on current theme
            lineNumberPaint.color = currentTextColor and 0x80FFFFFF.toInt() // 50% opacity

            val clipBounds = canvas.clipBounds
            val layout = layout ?: return

            val offset = clipBounds.left + (digitWidth / 2f).toInt()
            var line = layout.getLineForVertical(clipBounds.top)

            while (line < lineCount) {
                val baseline = getLineBounds(line, null)
                canvas.drawText("${line + 1}", offset.toFloat(), baseline.toFloat(), lineNumberPaint)
                line++

                if (baseline >= clipBounds.bottom) break
            }
        }

        override fun onTextChanged(
            text: CharSequence?,
            start: Int,
            lengthBefore: Int,
            lengthAfter: Int
        ) {
            super.onTextChanged(text, start, lengthBefore, lengthAfter)
            scheduleValidation()
        }

        private fun scheduleValidation() {
            // Remove previous validation request
            validationHandler.removeCallbacks(textChangeRunnable)

            // Schedule new validation with throttling
            validationHandler.postDelayed(textChangeRunnable, VALIDATION_DELAY_MS)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            validationHandler.removeCallbacks(textChangeRunnable)
            scope.cancel()
        }

        companion object {
            private const val VALIDATION_DELAY_MS = 1000L
        }
    }
}

/**
 * Extensions for easier usage
 */

/**
 * Show layout edit dialog with lambda callbacks
 */
fun Context.showLayoutEditDialog(
    initialText: String = "",
    allowRemove: Boolean = false,
    onValidate: (String) -> String? = { null },
    onSelect: (String?) -> Unit
) {
    CustomLayoutEditDialog.show(
        context = this,
        initialText = initialText,
        allowRemove = allowRemove,
        callback = object : CustomLayoutEditDialog.Callback {
            override fun onSelect(text: String?) = onSelect(text)
            override fun validate(text: String): String? = onValidate(text)
        }
    )
}

/**
 * Common validation functions for layout text
 */
object LayoutValidators {

    /**
     * Basic validation for layout format
     */
    fun validateBasicFormat(text: String): String? {
        if (text.isBlank()) {
            return "Layout cannot be empty"
        }

        val lines = text.trim().split('\n')
        if (lines.isEmpty()) {
            return "Layout must contain at least one row"
        }

        // Check for extremely long lines that might cause UI issues
        lines.forEach { line ->
            if (line.length > 100) {
                return "Line too long: maximum 100 characters per line"
            }
        }

        return null
    }

    /**
     * Advanced validation for keyboard layout structure
     */
    fun validateKeyboardStructure(text: String): String? {
        val basicError = validateBasicFormat(text)
        if (basicError != null) return basicError

        val lines = text.trim().split('\n')

        // Check for reasonable number of rows
        if (lines.size > 10) {
            return "Too many rows: maximum 10 rows allowed"
        }

        // Check each line for valid key format
        lines.forEachIndexed { index, line ->
            val keys = line.trim().split(Regex("\\s+"))

            if (keys.size > 20) {
                return "Row ${index + 1}: Too many keys (maximum 20 per row)"
            }

            keys.forEach { key ->
                if (key.isNotEmpty() && key.any { it.isWhitespace() }) {
                    return "Row ${index + 1}: Invalid key format '$key'"
                }
            }
        }

        return null
    }

    /**
     * Validation with character restrictions
     */
    fun validateWithCharacterRestrictions(text: String): String? {
        val structureError = validateKeyboardStructure(text)
        if (structureError != null) return structureError

        // Check for invalid characters that might cause issues
        val invalidChars = text.filter {
            !it.isLetterOrDigit() &&
            !it.isWhitespace() &&
            it !in ".,;:!?\"'()[]{}+-*/=<>@#$%^&|~`"
        }

        if (invalidChars.isNotEmpty()) {
            return "Invalid characters found: ${invalidChars.toSet().joinToString(", ")}"
        }

        return null
    }
}