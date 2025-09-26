package tribixbite.keyboard2

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.ListView

/**
 * A non-scrollable ListView that can be embedded in a larger ScrollView
 *
 * This custom ListView prevents scrolling by overriding onMeasure to expand
 * to show all items without scrolling. Essential for settings screens where
 * the ListView is inside a ScrollView.
 *
 * Credits: Dedaniya HirenKumar
 * https://stackoverflow.com/questions/18813296/non-scrollable-listview-inside-scrollview
 */
open class NonScrollListView : ListView {

    /**
     * Constructor for programmatic creation
     */
    constructor(context: Context) : super(context)

    /**
     * Constructor for XML inflation
     */
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    /**
     * Constructor for XML inflation with style
     */
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    /**
     * Override onMeasure to disable scrolling by making the ListView
     * expand to accommodate all its items
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Create a custom height measure spec that allows the ListView
        // to expand to its full content height
        val customHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
            Integer.MAX_VALUE shr 2, // Use bit shift for performance (equivalent to / 4)
            MeasureSpec.AT_MOST
        )

        // Measure with unlimited height
        super.onMeasure(widthMeasureSpec, customHeightMeasureSpec)

        // Set the layout params height to the measured height
        // This ensures the ListView shows all items without scrolling
        layoutParams?.let { params ->
            params.height = measuredHeight
        }
    }
}