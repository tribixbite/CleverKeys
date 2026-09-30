package tribixbite.cleverkeys.popover

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.max
import kotlin.math.min
import tribixbite.cleverkeys.Theme
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * Draws an open [SubkeyPopoverState] over the keyboard (docs/specs/subkey-popover.md).
 *
 * Owned by `Keyboard2View` and called at the end of its `onDraw`, so the popover shares the key
 * view's coordinates and touch clamping exactly. All paints and rects are allocated once;
 * [draw] allocates nothing per frame (the key view's draw path rule, core-keyboard-system.md).
 *
 * Motion:
 * - open: the panel grows from 85 % and fades in over [SubkeyPopoverState.OPEN_ANIM_MS];
 * - selection: the selected cell springs from 1.0 to [ACTIVE_SCALE] (overshoot) over
 *   [SubkeyPopoverState.SELECT_ANIM_MS];
 * - dwell: from [SubkeyPopoverState.DWELL_RING_START_MS] a ring fills around the selected
 *   assigned cell, completing when the edit screen opens.
 */
class SubkeyPopoverRenderer {

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val openInterp = DecelerateInterpolator(1.6f)
    private val selectInterp = OvershootInterpolator(2.2f)

    /**
     * Draw [state] at time [now] ([SubkeyPopoverState.now]). Returns true while an animation is
     * still running, so the caller schedules another frame.
     */
    fun draw(canvas: Canvas, state: SubkeyPopoverState, now: Long, theme: Theme, keyPaints: Theme.Computed.Key): Boolean {
        val openT = ((now - state.openedAt).toFloat() / SubkeyPopoverState.OPEN_ANIM_MS).coerceIn(0f, 1f)
        val open = openInterp.getInterpolation(openT)
        val selectT = ((now - state.activeSince).toFloat() / SubkeyPopoverState.SELECT_ANIM_MS).coerceIn(0f, 1f)
        val cw = state.cellWidth
        val ch = state.cellHeight

        // Scrim: dims the keyboard behind, focusing the grid.
        scrimPaint.color = Color.argb((SCRIM_ALPHA * open).toInt(), 0, 0, 0)
        canvas.drawPaint(scrimPaint)

        canvas.save()
        val panelScale = 0.85f + 0.15f * open
        canvas.scale(panelScale, panelScale, state.centreX, state.centreY)

        // Panel behind the grid.
        val pad = min(cw, ch) * PANEL_PAD
        rect.set(state.centreX - 1.5f * cw - pad, state.centreY - 1.5f * ch - pad,
            state.centreX + 1.5f * cw + pad, state.centreY + 1.5f * ch + pad)
        panelPaint.color = withAlpha(panelColor(theme), (PANEL_ALPHA * open).toInt())
        val radius = min(cw, ch) * CORNER
        canvas.drawRoundRect(rect, radius * 1.4f, radius * 1.4f, panelPaint)

        // Centre cell: the held key, dimmed, with the neutral zone outlined so the user learns
        // (and after tuning, sees) where letting go cancels.
        val nw = cw * state.neutralWidthFraction
        val nh = ch * state.neutralHeightFraction
        rect.set(state.centreX - nw / 2f, state.centreY - nh / 2f, state.centreX + nw / 2f, state.centreY + nh / 2f)
        strokePaint.strokeWidth = max(1f, min(cw, ch) * 0.02f)
        strokePaint.color = withAlpha(theme.subLabelColor, (NEUTRAL_OUTLINE_ALPHA * open).toInt())
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        drawText(canvas, keyPaints, state.keyLabel, state.keyLabelUsesKeyFont, state.centreX, state.centreY,
            cw, ch * 0.34f, withAlpha(theme.labelColor, (CENTRE_LABEL_ALPHA * open).toInt()))

        // Slots, the selected one last so it draws above its neighbours when enlarged.
        var animating = openT < 1f || selectT < 1f
        for (slot in state.slots) {
            if (slot.direction != state.active) drawSlot(canvas, state, slot, 1f, false, open, theme, keyPaints)
        }
        val activeSlot = state.slot(state.active)
        if (activeSlot != null) {
            val scale = 1f + (ACTIVE_SCALE - 1f) * selectInterp.getInterpolation(selectT)
            drawSlot(canvas, state, activeSlot, scale, true, open, theme, keyPaints)
            if (SubkeyPopoverSlots.isEditable(activeSlot) && state.keyCode != null) {
                animating = drawDwellRing(canvas, state, activeSlot.direction, scale, now, theme) || animating
            }
        }
        canvas.restore()
        return animating
    }

    private fun drawSlot(
        canvas: Canvas, state: SubkeyPopoverState, slot: PopoverSlot, scale: Float, selected: Boolean,
        open: Float, theme: Theme, keyPaints: Theme.Computed.Key,
    ) {
        val cx = cellX(state, slot.direction)
        val cy = cellY(state, slot.direction)
        val w = state.cellWidth * CELL_FILL * scale
        val h = state.cellHeight * CELL_FILL * scale
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val radius = min(w, h) * CORNER
        val label = state.label(slot.direction)
        val alpha = (255 * open).toInt()

        if (label.text == null) {
            // Empty: an outline and a "+" when the slot can be assigned, nothing otherwise.
            if (state.keyCode == null) return
            strokePaint.strokeWidth = max(1f, min(w, h) * 0.025f)
            strokePaint.color = withAlpha(if (selected) theme.activatedColor else theme.subLabelColor,
                if (selected) alpha else (alpha * 0.45f).toInt())
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
            drawText(canvas, keyPaints, "+", false, cx, cy, w, h * 0.42f,
                withAlpha(if (selected) theme.activatedColor else theme.subLabelColor,
                    if (selected) alpha else (alpha * 0.6f).toInt()))
            return
        }
        cellPaint.color = withAlpha(if (selected) theme.colorKeyActivated else theme.colorKey, alpha)
        canvas.drawRoundRect(rect, radius, radius, cellPaint)
        if (selected) {
            strokePaint.strokeWidth = max(1.5f, min(w, h) * 0.035f)
            strokePaint.color = withAlpha(theme.activatedColor, alpha)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }
        drawText(canvas, keyPaints, label.text, label.useKeyFont, cx, cy, w, h * 0.46f,
            withAlpha(if (selected) theme.activatedColor else theme.labelColor, alpha))
    }

    /** Fill a ring around the selected cell while the finger dwells; true while filling. */
    private fun drawDwellRing(
        canvas: Canvas, state: SubkeyPopoverState, direction: SwipeDirection, scale: Float, now: Long, theme: Theme,
    ): Boolean {
        val held = now - state.activeSince
        if (held < SubkeyPopoverState.DWELL_RING_START_MS) return true  // ring not started yet
        val span = (SubkeyPopoverState.DWELL_EDIT_MS - SubkeyPopoverState.DWELL_RING_START_MS).toFloat()
        val progress = ((held - SubkeyPopoverState.DWELL_RING_START_MS) / span).coerceIn(0f, 1f)
        val cx = cellX(state, direction)
        val cy = cellY(state, direction)
        val r = min(state.cellWidth, state.cellHeight) * CELL_FILL * scale * 0.62f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        ringPaint.strokeWidth = max(2f, r * 0.12f)
        ringPaint.color = withAlpha(theme.activatedColor, 220)
        canvas.drawArc(rect, -90f, 360f * progress, false, ringPaint)
        return progress < 1f
    }

    private fun cellX(state: SubkeyPopoverState, direction: SwipeDirection): Float =
        state.centreX + SubkeyPopoverGeometry.cellColumn(direction) * state.cellWidth

    private fun cellY(state: SubkeyPopoverState, direction: SwipeDirection): Float =
        state.centreY + SubkeyPopoverGeometry.cellRow(direction) * state.cellHeight

    /** Draw [text] centred on ([cx], [cy]), shrunk to fit [maxWidth]. */
    private fun drawText(
        canvas: Canvas, keyPaints: Theme.Computed.Key, text: String, keyFont: Boolean,
        cx: Float, cy: Float, maxWidth: Float, size: Float, color: Int,
    ) {
        val p = keyPaints.label_paint(keyFont, color, size)
        // label_paint folds the theme's label brightness into the alpha; keep the requested one.
        p.alpha = Color.alpha(color)
        val align = p.textAlign
        p.textAlign = Paint.Align.CENTER
        val width = p.measureText(text)
        val limit = maxWidth * 0.86f
        if (width > limit && width > 0f) p.textSize = size * limit / width
        canvas.drawText(text, cx, cy - (p.ascent() + p.descent()) / 2f, p)
        p.textAlign = align
    }

    /**
     * The panel behind the cells: the keyboard's own background, so the cells (key colour) read
     * as a small keyboard. XML themes without `colorKeyboard` (0) get the key colour darkened
     * instead; `colorKeyboardSurface` is no use here since XML themes set it to the key colour.
     */
    private fun panelColor(theme: Theme): Int {
        if (theme.colorKeyboardBackground != 0) return theme.colorKeyboardBackground
        val k = theme.colorKey
        return Color.rgb(
            (Color.red(k) * PANEL_DARKEN).toInt(),
            (Color.green(k) * PANEL_DARKEN).toInt(),
            (Color.blue(k) * PANEL_DARKEN).toInt()
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private companion object {
        const val ACTIVE_SCALE = 1.3f
        const val CELL_FILL = 0.9f
        const val CORNER = 0.18f
        const val PANEL_PAD = 0.08f
        const val PANEL_ALPHA = 245
        const val PANEL_DARKEN = 0.65f
        const val SCRIM_ALPHA = 90
        const val NEUTRAL_OUTLINE_ALPHA = 110
        const val CENTRE_LABEL_ALPHA = 150
    }
}
