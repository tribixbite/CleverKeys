package tribixbite.cleverkeys

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Manages tooltip popup for displaying emoji names on long-press.
 *
 * Uses PopupWindow instead of Toast for reliable display within IME context:
 * - PopupWindow is anchored to views within the IME window
 * - No queuing issues like Toast
 * - Full control over positioning and styling
 * - Works reliably across all Android API levels (21+)
 *
 * @since v1.2.6
 */
class EmojiTooltipManager(context: Context) {

    private val tooltipView: View
    private val emojiCharView: TextView
    private val emojiNameView: TextView
    private val popupWindow: PopupWindow

    init {
        @Suppress("DEPRECATION")
        tooltipView = LayoutInflater.from(context).inflate(
            context.resources.getIdentifier("emoji_tooltip", "layout", context.packageName),
            null
        )
        emojiCharView = tooltipView.findViewById(
            context.resources.getIdentifier("emoji_char", "id", context.packageName)
        )
        emojiNameView = tooltipView.findViewById(
            context.resources.getIdentifier("emoji_name", "id", context.packageName)
        )

        popupWindow = PopupWindow(
            tooltipView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            // Non-interactive tooltip
            isFocusable = false
            isTouchable = false
            isOutsideTouchable = false
            // Elevation for shadow on API 21+
            elevation = 8f
        }
    }

    /**
     * Show tooltip anchored above the given view.
     *
     * @param anchor The view to anchor the tooltip to (the emoji cell)
     * @param emojiChar The emoji character to display
     * @param emojiName The emoji name to display
     */
    fun show(anchor: View, emojiChar: String, emojiName: String) {
        // Update content
        emojiCharView.text = emojiChar
        emojiNameView.text = emojiName

        // Measure tooltip to calculate position
        tooltipView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val tooltipWidth = tooltipView.measuredWidth
        val tooltipHeight = tooltipView.measuredHeight

        // Get anchor position on screen
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)

        // Calculate position: center horizontally above the anchor
        // Show above the finger to not obscure the emoji
        val x = anchorLocation[0] + (anchor.width - tooltipWidth) / 2
        val y = anchorLocation[1] - tooltipHeight - 16  // 16px gap above anchor

        // Dismiss any existing popup first
        if (popupWindow.isShowing) {
            popupWindow.dismiss()
        }

        // Show at calculated position
        // Use showAtLocation for precise positioning within IME window
        try {
            popupWindow.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        } catch (e: Exception) {
            // Fallback: try showAsDropDown if showAtLocation fails
            android.util.Log.w("EmojiTooltipManager", "showAtLocation failed, trying showAsDropDown", e)
            try {
                val offsetX = (anchor.width - tooltipWidth) / 2
                val offsetY = -anchor.height - tooltipHeight - 16
                popupWindow.showAsDropDown(anchor, offsetX, offsetY, Gravity.TOP or Gravity.START)
            } catch (e2: Exception) {
                android.util.Log.e("EmojiTooltipManager", "Failed to show tooltip", e2)
            }
        }
    }

    /**
     * Dismiss the tooltip if showing.
     */
    fun dismiss() {
        if (popupWindow.isShowing) {
            try {
                popupWindow.dismiss()
            } catch (e: Exception) {
                android.util.Log.w("EmojiTooltipManager", "Error dismissing popup", e)
            }
        }
    }

    /**
     * Check if tooltip is currently showing.
     */
    fun isShowing(): Boolean = popupWindow.isShowing
}
