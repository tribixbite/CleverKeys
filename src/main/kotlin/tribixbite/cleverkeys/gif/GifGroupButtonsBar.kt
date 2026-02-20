package tribixbite.cleverkeys.gif

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import tribixbite.cleverkeys.R

/**
 * Scrollable horizontal bar displaying GIF emotion category buttons.
 *
 * Wraps a LinearLayout inside a HorizontalScrollView so all 18 categories
 * (recently used + 17 emotions) are accessible without being squeezed.
 *
 * Fires [onCategoryChanged] when user taps a category. The parent wires
 * this callback to the GifGridManager to switch displayed GIFs.
 */
class GifGroupButtonsBar(context: Context, attrs: AttributeSet) : HorizontalScrollView(context, attrs) {

    private val buttonContainer: LinearLayout

    /** Fired when a category button is tapped — parent should call gifGrid.setCategory(). */
    var onCategoryChanged: ((GifCategory) -> Unit)? = null

    /** Fired alongside onCategoryChanged — used to clear search input. */
    private var onCategorySelected: (() -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false

        buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        }

        // Add "recently used" clock button first
        buttonContainer.addView(
            GifGroupButton(context, GifCategory.RECENTLY_USED),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                width = dpToPx(44)
            }
        )

        // Add "all" globe button
        buttonContainer.addView(
            GifGroupButton(context, GifCategory.ALL),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                width = dpToPx(44)
            }
        )

        // Add a button for each browsable emotion category
        for (category in GifCategory.browsableCategories()) {
            buttonContainer.addView(
                GifGroupButton(context, category),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    width = dpToPx(44)
                }
            )
        }

        addView(buttonContainer)
    }

    /**
     * Set callback for when a category button is tapped.
     * Used to clear search query when user browses by category.
     */
    fun setOnCategorySelectedListener(listener: () -> Unit) {
        this.onCategorySelected = listener
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    /**
     * Individual category button showing the emoji icon for each GIF emotion.
     */
    inner class GifGroupButton(
        context: Context,
        private val category: GifCategory
    ) : Button(ContextThemeWrapper(context, R.style.emojiTypeButton), null, 0),
        View.OnTouchListener {

        init {
            text = category.icon
            contentDescription = category.displayName
            setOnTouchListener(this)
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_DOWN) {
                return false
            }
            // Notify search clear callback
            onCategorySelected?.invoke()
            // Notify category change callback
            onCategoryChanged?.invoke(category)
            return true
        }
    }
}
