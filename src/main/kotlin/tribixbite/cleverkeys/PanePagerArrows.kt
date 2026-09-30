package tribixbite.cleverkeys

import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes

/**
 * Page arrows for the keyboard's GIF and clipboard panes, mirrored for right-to-left locales.
 *
 * The panes' pagination bars are horizontal `LinearLayout`s: under an RTL configuration
 * (app locale fa) Android lays them out right-to-left, so the "previous page" button sits on
 * the RIGHT and "next page" on the LEFT. The buttons draw text glyphs (◀ / ▶), which, unlike
 * `Icons.AutoMirrored.*` in the Compose settings (3f886d86), do not flip on their own — they
 * pointed inwards at the page counter. In RTL reading order "previous" is to the right, so its
 * arrow must point right, and "next" must point left: the glyphs swap.
 *
 * The arrows are meant as reading direction ("earlier page" / "later page"), not a physical
 * direction, which is why they mirror.
 */
object PanePagerArrows {

    /** The string resources to show on the previous-page and next-page buttons. */
    data class Glyphs(@StringRes val prev: Int, @StringRes val next: Int)

    /**
     * [R.string.glyph_page_prev] is the left-pointing glyph and [R.string.glyph_page_next] the
     * right-pointing one; under RTL each button takes the other.
     */
    fun glyphsFor(rtl: Boolean): Glyphs =
        if (rtl) Glyphs(prev = R.string.glyph_page_next, next = R.string.glyph_page_prev)
        else Glyphs(prev = R.string.glyph_page_prev, next = R.string.glyph_page_next)

    /**
     * Sets both buttons' glyphs from the layout direction of their context's configuration —
     * the same configuration that decides the bar's child order, so arrow and position agree.
     * Read from the configuration rather than [View.getLayoutDirection] because the view's
     * resolved direction is only final once it is attached, and the panes are bound before that.
     */
    fun apply(prev: TextView?, next: TextView?) {
        val anchor = prev ?: next ?: return
        val rtl = anchor.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val glyphs = glyphsFor(rtl)
        prev?.setText(glyphs.prev)
        next?.setText(glyphs.next)
    }
}
