package tribixbite.cleverkeys

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.ListView

/**
 * A non-scrollable list view that can be embedded in a bigger ScrollView.
 * Credits to Dedaniya HirenKumar in
 * https://stackoverflow.com/questions/18813296/non-scrollable-listview-inside-scrollview
 */
open class NonScrollListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ListView(context, attrs, defStyle) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMeasureSpecCustom = MeasureSpec.makeMeasureSpec(
            Int.MAX_VALUE shr 2, MeasureSpec.AT_MOST
        )
        super.onMeasure(widthMeasureSpec, heightMeasureSpecCustom)
        layoutParams.height = measuredHeight
    }
}
