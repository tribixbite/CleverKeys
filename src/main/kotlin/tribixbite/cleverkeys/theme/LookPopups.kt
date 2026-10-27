package tribixbite.cleverkeys.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pop-ups and hint styling of a [KeyboardLook]: the key-press preview each product shows, the
 * colours of that preview and of the long-press popover, and how corner labels are drawn when
 * the user shows them on a look. Pure JVM (`LookPopupsTest`); `Keyboard2View` and
 * `SubkeyPopoverRenderer` draw what these resolve.
 *
 * References (Pixel 8 Pro captures 2026-10-28, `build/oct28-looks2/`, and research §1–§4):
 *  - Gboard: a stadium-shaped bubble about 1.3 keys wide and 1.25 keys tall in the letter-key
 *    colour, its foot just over the pressed key's top edge, label larger than on the key.
 *  - iOS: the key-pop balloon — a head 12 pt wider than the key on each side and ~1.16 keys
 *    tall, joined to the key by a neck, in the key colour, with a much larger label.
 *  - SwiftKey (Fluent): a rectangle exactly as wide as the key, reaching about one row above
 *    it, in the press colour (`#E3E3E3` / `#404040`), label in its top part.
 *  - Samsung: a rounded plate a little wider than the key above it (`#FCFCFF` / `#2D2D30`).
 */

/** Shape of a look's key-press preview. */
enum class LookPreviewShape {
    /** No preview (every non-look theme; CleverKeys never drew one before looks). */
    NONE,
    /** Gboard: a stadium bubble above the key. */
    BUBBLE,
    /** iOS: a wide balloon head joined to the key by a neck. */
    BALLOON,
    /** SwiftKey: a key-wide column rising one row above the key. */
    COLUMN,
    /** Samsung: a rounded plate above the key. */
    PLATE,
}

/**
 * A look's key-press preview. Sizes are in key units so they follow any keyboard height:
 * [widthScale] × the key's width, [heightScale] × the key's height; [labelScale] multiplies the
 * key's main-label size; [cornerScale] × the key height is the corner radius ([BUBBLE] and the
 * [BALLOON] head are rounded by their own rule).
 */
data class LookPreview(
    val shape: LookPreviewShape,
    val widthScale: Float = 1f,
    val heightScale: Float = 1f,
    val labelScale: Float = 1f,
    val cornerScale: Float = 0.2f,
    /** Whether a soft shadow is drawn under the preview (Gboard's elevation). */
    val shadow: Boolean = false,
) {
    companion object {
        val NONE = LookPreview(LookPreviewShape.NONE)
    }
}

/**
 * Where a preview is drawn, in view pixels. The [head] holds the label; [neck] (BALLOON only,
 * else empty) joins it to the key. [labelCenterY] is the label's vertical centre.
 */
data class LookPreviewFrame(
    val headLeft: Float, val headTop: Float, val headRight: Float, val headBottom: Float,
    val headRadius: Float,
    val neckLeft: Float, val neckTop: Float, val neckRight: Float, val neckBottom: Float,
    val neckRadius: Float,
    val labelCenterX: Float, val labelCenterY: Float,
    val labelSize: Float,
) {
    val hasNeck: Boolean get() = neckBottom > neckTop
}

/**
 * Key-press preview policy and geometry.
 *
 * Precedence (docs/specs/layout-system.md "Keyboard looks"): CleverKeys had no key-press
 * preview and no preference for one. A look shows its product's default preview (iOS, Gboard as
 * shipped on the Pixel, SwiftKey and Samsung all show one) unless the user turned off Appearance
 * → "Key-press preview on familiar looks" (`look_key_preview`, default on). Non-look themes draw
 * none, exactly as before.
 */
object LookPreviewPolicy {

    /** Whether a preview is drawn for a pressed key: a look with a preview, the pref on, a visible character. */
    fun shows(preview: LookPreview?, prefOn: Boolean, isCharacter: Boolean, isSpace: Boolean): Boolean =
        preview != null && preview.shape != LookPreviewShape.NONE && prefOn && isCharacter && !isSpace

    /**
     * The preview frame for a key at ([keyLeft], [keyTop]) of size [keyW] × [keyH] whose corner
     * radius is [keyRadius], in a view [viewWidth] px wide. [mainLabelSize] is the key's label
     * size. The keyboard window cannot draw above its own top edge, so a preview that would
     * rise above y = 0 (top row) is moved down until it fits, and one that would cross a side
     * edge is moved inside.
     */
    fun frame(
        preview: LookPreview, keyLeft: Float, keyTop: Float, keyW: Float, keyH: Float,
        keyRadius: Float, viewWidth: Float, mainLabelSize: Float,
    ): LookPreviewFrame {
        val cx = keyLeft + keyW / 2f
        val w = keyW * preview.widthScale
        val h = keyH * preview.heightScale
        var headTop = 0f
        var headBottom = 0f
        var headRadius = 0f
        var neckTop = 0f
        var neckBottom = 0f
        var neckRadius = 0f
        var labelCy = 0f
        when (preview.shape) {
            LookPreviewShape.NONE, LookPreviewShape.BUBBLE -> {
                // Foot overlaps the key's top edge by a sixth of the key.
                headBottom = keyTop + keyH / 6f
                headTop = headBottom - h
                headRadius = min(w, h) / 2f
                labelCy = (headTop + headBottom) / 2f
            }
            LookPreviewShape.BALLOON -> {
                // Head ends 0.37 keys above the key (iOS: 66 pt top, 50 pt head over a 43 pt key);
                // the neck spans head to key bottom at the key's own width.
                headBottom = keyTop - keyH * 0.37f
                headTop = headBottom - h
                headRadius = keyH * preview.cornerScale
                neckTop = headBottom - headRadius
                neckBottom = keyTop + keyH
                neckRadius = keyRadius
                labelCy = (headTop + headBottom) / 2f
            }
            LookPreviewShape.COLUMN -> {
                // From the key's bottom up [heightScale] keys; the label sits in the top key-height.
                headBottom = keyTop + keyH
                headTop = headBottom - h
                headRadius = keyRadius
                labelCy = headTop + keyH / 2f
            }
            LookPreviewShape.PLATE -> {
                headBottom = keyTop - keyH * 0.08f
                headTop = headBottom - h
                headRadius = keyH * preview.cornerScale
                labelCy = (headTop + headBottom) / 2f
            }
        }
        // Clamp inside the view: down for the top row, sideways for the edge keys.
        val dy = max(0f, -headTop)
        headTop += dy; headBottom += dy; labelCy += dy
        if (neckBottom > neckTop) neckTop += dy
        var left = cx - w / 2f
        var right = cx + w / 2f
        val dx = when {
            left < 0f -> -left
            right > viewWidth -> viewWidth - right
            else -> 0f
        }
        left += dx; right += dx
        return LookPreviewFrame(
            headLeft = left, headTop = headTop, headRight = right, headBottom = headBottom,
            headRadius = headRadius,
            neckLeft = keyLeft, neckTop = neckTop, neckRight = keyLeft + keyW, neckBottom = neckBottom,
            neckRadius = neckRadius,
            labelCenterX = cx + dx, labelCenterY = labelCy,
            labelSize = mainLabelSize * preview.labelScale,
        )
    }
}

/** The resolved colours of a look's preview and long-press popover ([LookPalette] 0 = default). */
data class LookPopupColors(
    /** Preview fill and popover panel. */
    val fill: Int,
    val label: Int,
    /** The selected popover alternative's fill. */
    val selected: Int,
    val selectedLabel: Int,
) {
    companion object {
        fun of(p: LookPalette): LookPopupColors = LookPopupColors(
            fill = p.popup.takeIf { it != 0 } ?: p.key,
            label = p.popupLabel.takeIf { it != 0 } ?: p.label,
            selected = p.popupSelected.takeIf { it != 0 } ?: p.keyLocked,
            selectedLabel = p.popupSelectedLabel.takeIf { it != 0 } ?: p.lockedLabel,
        )
    }
}

/**
 * How corner labels (gesture hints) look on a look when the user shows them (`look_show_hints`),
 * so they read as the product's own hints rather than CleverKeys' default clutter: a size that
 * is a fixed fraction of the look's main label ([sizeFraction]; Gboard's top-right digits are
 * about 40 % of the letter), the look's [weight], and the look's hint colour
 * ([LookPalette.hint], already the theme's sub-label colour).
 */
data class LookHintStyle(
    val sizeFraction: Float,
    val weight: Int,
) {
    companion object {
        /** Used by looks that declare none: close to CleverKeys' own proportions. */
        val DEFAULT = LookHintStyle(sizeFraction = 0.42f, weight = 400)
    }
}

/** Resolved hint drawing for one key ([LookHintLayout.resolve]). */
data class LookHintMetrics(
    /** Text size in px. */
    val size: Float,
    /** Inset from the key edge for an edge-centred slot (N, S, E, W). */
    val edgeInset: Float,
    /** Inset from both edges for a corner slot (NW, NE, SW, SE), clear of the rounded corner. */
    val cornerInset: Float,
)

/**
 * Size and placement of corner labels on a look. Pure; `Keyboard2View.drawSubLabel` applies it
 * when a look is effective and draws its labels.
 */
object LookHintLayout {

    /** A corner label never takes more than this fraction of the key height. */
    const val MAX_HEIGHT_FRACTION = 0.3f

    /**
     * [mainLabelSize] is the look's main label size (already scaled by the look), [userScale]
     * the user's secondary-label size multiplier (`secondary_label_size_scale`, so the user's
     * setting still counts), [basePadding] the user's key padding, [radius] the key's corner
     * radius (a pill's is half its height), [keyH] the key height.
     */
    fun resolve(
        style: LookHintStyle, mainLabelSize: Float, userScale: Float, basePadding: Float,
        radius: Float, keyH: Float,
    ): LookHintMetrics = LookHintMetrics(
        size = size(style, mainLabelSize, userScale, keyH),
        edgeInset = basePadding,
        cornerInset = cornerInset(basePadding, radius),
    )

    /** Hint text size in px (allocation-free part of [resolve], used per frame by the view). */
    fun size(style: LookHintStyle, mainLabelSize: Float, userScale: Float, keyH: Float): Float =
        min(mainLabelSize * style.sizeFraction * userScale, keyH * MAX_HEIGHT_FRACTION)

    /**
     * Corner-slot inset: the rounded corner's arc passes radius × (1 − 1/√2) inside the corner on
     * the diagonal, so a corner label inset by at least that stays on the key, even on a pill.
     */
    fun cornerInset(basePadding: Float, radius: Float): Float =
        max(basePadding, radius * (1f - 1f / sqrt(2f)))
}
