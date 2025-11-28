package tribixbite.keyboard2

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ListView

/**
 * A scrollable ListView that limits its maximum height.
 * Used for pinned clipboard items - shows max 2 items with internal scrolling.
 */
open class MaxHeightListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ListView(context, attrs, defStyle) {

    private var maxHeight = -1

    init {
        if (attrs != null) {
            // Read maxHeight from XML attributes
            for (i in 0 until attrs.attributeCount) {
                val name = attrs.getAttributeName(i)
                if ("maxHeight" == name) {
                    maxHeight = attrs.getAttributeIntValue(i, -1)
                    break
                }
            }
        }
    }

    fun setMaxHeight(maxHeight: Int) {
        this.maxHeight = maxHeight
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var adjustedHeightSpec = heightMeasureSpec

        if (maxHeight > 0) {
            // Limit height to maxHeight
            val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
            val heightSize = View.MeasureSpec.getSize(heightMeasureSpec)

            if (heightMode == View.MeasureSpec.UNSPECIFIED || heightSize > maxHeight) {
                adjustedHeightSpec = View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
            }
        }

        super.onMeasure(widthMeasureSpec, adjustedHeightSpec)

        // Respect minHeight if set
        val minHeight = suggestedMinimumHeight
        if (minHeight > 0) {
            val measuredHeight = measuredHeight
            if (measuredHeight < minHeight) {
                // Enforce minHeight
                setMeasuredDimension(measuredWidth, minHeight)
            }
        }
    }
}
