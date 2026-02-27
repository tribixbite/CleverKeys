/*
 * Inline Autofill utilities for seamless password manager integration.
 * Based on Android's Inline Suggestions API (API 30+).
 *
 * This provides automatic autofill suggestions in the keyboard's suggestion bar
 * without requiring manual button presses.
 */
package tribixbite.cleverkeys.autofill

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.util.Size
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import tribixbite.cleverkeys.R

/**
 * Utilities for inline autofill integration.
 *
 * The Inline Suggestions API allows password managers to display suggestions
 * directly in the keyboard's suggestion strip, providing a seamless user experience.
 */
@RequiresApi(Build.VERSION_CODES.R)
object InlineAutofillUtils {

    /**
     * Creates an InlineSuggestionsRequest that describes how the keyboard
     * wants to display inline autofill suggestions.
     *
     * @param context The context for accessing resources
     * @return An InlineSuggestionsRequest configured with styling specs
     */
    fun createInlineSuggestionsRequest(context: Context): InlineSuggestionsRequest {
        // #109: Improved chip styling for better readability and appearance
        val backgroundColor = 0xFF353535.toInt()  // Slightly lighter dark gray for contrast
        val textColor = 0xFFFFFFFF.toInt()
        val hintColor = 0xFFBBBBBB.toInt()  // Brighter hint for subtitle visibility

        // Build the styles for inline suggestion chips
        val stylesBuilder = UiVersions.newStylesBuilder()
        val chipPadding = (4 * context.resources.displayMetrics.density).toInt() // 4dp padding
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackground(
                        Icon.createWithResource(context, R.drawable.autofill_chip_background)
                            .setTint(backgroundColor)
                    )
                    .setPadding(chipPadding, 0, chipPadding, 0)
                    .build()
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(
                        Icon.createWithResource(context, R.drawable.autofill_chip_background)
                            .setTint(backgroundColor)
                    )
                    .setPadding(chipPadding * 2, chipPadding, chipPadding * 2, chipPadding)
                    .build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setLayoutMargin(0, 0, chipPadding, 0)
                    .build()
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(textColor)
                    .setTextSize(14f)  // Larger title for readability
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(hintColor)
                    .setTextSize(11f)  // Slightly larger subtitle
                    .build()
            )
            .setEndIconStyle(
                ImageViewStyle.Builder()
                    .setLayoutMargin(chipPadding, 0, 0, 0)
                    .build()
            )
            .build()

        stylesBuilder.addStyle(style)
        val stylesBundle = stylesBuilder.build()

        // Get suggestion strip height for proper sizing
        val height = context.resources.getDimensionPixelSize(R.dimen.suggestion_strip_height)
        // #109: Use display width for max chip size so names aren't truncated
        val displayWidth = context.resources.displayMetrics.widthPixels
        val minSize = Size(100, height)
        val maxSize = Size(displayWidth, height)

        // Create presentation specs - multiple specs needed for some password managers
        val presentationSpecs = mutableListOf<InlinePresentationSpec>()
        repeat(3) {
            presentationSpecs.add(
                InlinePresentationSpec.Builder(minSize, maxSize)
                    .setStyle(stylesBundle)
                    .build()
            )
        }

        return InlineSuggestionsRequest.Builder(presentationSpecs)
            .setMaxSuggestionCount(6)
            .build()
    }

    /**
     * Creates a View containing the inline suggestions to be displayed
     * in the keyboard's suggestion strip.
     *
     * @param inlineSuggestions List of inline suggestions from the autofill service
     * @param context The context for creating views
     * @return A clipped view containing the scrollable suggestions
     */
    fun createView(
        inlineSuggestions: List<InlineSuggestion>,
        context: Context
    ): InlineContentClipView {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // Inflate each inline suggestion and add to container
        // #48: Log inflate failures to aid debugging with password managers
        for ((index, suggestion) in inlineSuggestions.withIndex()) {
            suggestion.inflate(
                context,
                Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                context.mainExecutor
            ) { view ->
                if (view != null) {
                    container.addView(view)
                } else {
                    android.util.Log.w("InlineAutofill",
                        "Suggestion $index inflate returned null (may require auth unlock)")
                }
            }
        }

        // Wrap in horizontal scroll view for multiple suggestions
        val scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(container)
        }

        // Wrap in clip view to properly bound the inline content
        return InlineContentClipView(context).apply {
            addView(scrollView)
        }
    }

    /**
     * A container for showing InlineContentViews that ensures they appear
     * only within the keyboard's bounds. This is necessary because InlineContentViews
     * are rendered by another process and would otherwise overlay other parts of the app.
     */
    class InlineContentClipView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : FrameLayout(context, attrs, defStyleAttr) {

        private val onDrawListener = ViewTreeObserver.OnDrawListener {
            clipDescendantInlineContentViews()
        }

        private val parentBounds = Rect()
        private val contentBounds = Rect()

        init {
            // Add a transparent surface view for proper z-ordering
            val backgroundView = SurfaceView(context).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSPARENT)
            }
            addView(backgroundView)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnDrawListener(onDrawListener)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            viewTreeObserver.removeOnDrawListener(onDrawListener)
        }

        private fun clipDescendantInlineContentViews() {
            parentBounds.right = width
            parentBounds.bottom = height
            clipDescendantInlineContentViews(this)
        }

        private fun clipDescendantInlineContentViews(root: View?) {
            if (root == null) return

            if (root is InlineContentView) {
                contentBounds.set(parentBounds)
                offsetRectIntoDescendantCoords(root, contentBounds)
                root.clipBounds = contentBounds
                return
            }

            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    clipDescendantInlineContentViews(root.getChildAt(i))
                }
            }
        }
    }
}
