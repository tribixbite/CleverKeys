package tribixbite.cleverkeys.popover

import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * Size of the popover's cells and of the view it must fit in, in the keyboard view's px.
 * Supplied by the view (`Pointers.IPointerEventHandler.subkeyPopoverMetrics`) because only it
 * knows the laid-out key and row sizes.
 */
data class SubkeyPopoverMetrics(
    val cellWidth: Float,
    val cellHeight: Float,
    val viewWidth: Float,
    val viewHeight: Float,
)

/**
 * One open popover. `Pointers` owns and mutates it on the UI thread; the view only reads it
 * while drawing.
 *
 * @property keyCode the held key's custom-mapping code, or null when the key cannot carry
 *   mappings (then empty slots are not assignable and are drawn blank).
 * @property keyLabel what the neutral centre cell shows (the held key's own label).
 */
class SubkeyPopoverState(
    val keyCode: String?,
    val keyLabel: String,
    val keyLabelUsesKeyFont: Boolean,
    val slots: List<PopoverSlot>,
    val restX: Float,
    val restY: Float,
    val centreX: Float,
    val centreY: Float,
    val cellWidth: Float,
    val cellHeight: Float,
    val neutralWidthFraction: Float,
    val neutralHeightFraction: Float,
    /** Uptime (ms) the popover opened; drives the open animation. */
    val openedAt: Long,
) {
    /** The selected slot's direction, or null while in the neutral zone. */
    var active: SwipeDirection? = null
        private set

    /** Uptime (ms) [active] last changed; drives the dwell border. */
    var activeSince: Long = openedAt
        private set

    /** Set once the finger has moved enough to select (see [SubkeyPopoverGeometry.isArmed]). */
    var armed: Boolean = false
        private set

    /** Handler `what` of the pending dwell message, -1 when none. */
    var dwellWhat: Int = -1

    /** Labels resolved once at open, so drawing allocates nothing per frame. */
    private val labels: Map<SwipeDirection, PopoverSlotLabel> =
        java.util.EnumMap<SwipeDirection, PopoverSlotLabel>(SwipeDirection::class.java).apply {
            for (s in slots) put(s.direction, SubkeyPopoverSlots.labelOf(s))
        }

    fun label(direction: SwipeDirection): PopoverSlotLabel = labels.getValue(direction)

    fun slot(direction: SwipeDirection?): PopoverSlot? =
        direction?.let { d -> slots.firstOrNull { it.direction == d } }

    /**
     * Feed a finger position (view px). Returns true when the selected slot changed, so the
     * caller restarts the dwell timer, ticks the haptic and redraws.
     */
    fun track(x: Float, y: Float, now: Long): Boolean {
        if (!armed) armed = SubkeyPopoverGeometry.isArmed(restX, restY, x, y, cellWidth, cellHeight)
        val next = if (armed) SubkeyPopoverGeometry.slotAt(
            x - centreX, y - centreY, cellWidth, cellHeight, neutralWidthFraction, neutralHeightFraction
        )?.takeIf(::isSelectable) else null
        if (next == active) return false
        active = next
        activeSince = now
        return true
    }

    /**
     * Whether the slot at [direction] can be selected. An empty slot on a key without a
     * [keyCode] is drawn blank and cannot be assigned, so selecting it (and ticking the haptic
     * for it) would react to a cell the user cannot see (audit 2026-10-08): it reads as the
     * neutral zone instead.
     */
    private fun isSelectable(direction: SwipeDirection): Boolean =
        keyCode != null || slot(direction) !is PopoverSlot.Empty

    companion object {
        /**
         * Monotonic ms clock shared by `Pointers` and the view. Not `SystemClock` so the gesture
         * logic runs unchanged in JVM tests (android.jar stubs throw there).
         */
        fun now(): Long = System.nanoTime() / 1_000_000

        /** Resting on an assigned slot this long opens its edit screen (owner spec: 3 s). */
        const val DWELL_EDIT_MS = 3_000L

        /** The dwell border starts filling after this, so ordinary selection shows none. */
        const val DWELL_RING_START_MS = 800L

        /** Open animation length. */
        const val OPEN_ANIM_MS = 140L

        /** Selected-slot scale-up animation length. */
        const val SELECT_ANIM_MS = 110L
    }
}

/** What the keyboard asks the assign/edit screen to do for (key, direction). */
data class SubkeyAssignRequest(
    val keyCode: String,
    val direction: SwipeDirection,
    val mode: Mode,
    /** A layout subkey sits in this slot (hidden by a mapping, or shown as the default). */
    val hasDefault: Boolean,
    /** The slot currently shows the user's custom mapping (not the layout's subkey). */
    val isCustom: Boolean,
    /** The slot's current label, for the edit screen's header; null when empty. */
    val currentLabel: String?,
    /** [currentLabel] is a key-font icon glyph (drawn with the key font, not as text). */
    val labelUsesKeyFont: Boolean = false,
) {
    enum class Mode { ASSIGN, EDIT }
}

/**
 * [SubkeyAssignRequest] as `SubkeyAssignActivity` intent extras, without `Intent` so the round
 * trip is host-testable (`SubkeyAssignExtrasTest`). [read] returns null for a missing or
 * unknown key code, direction or mode: the screen then closes instead of guessing.
 */
internal object SubkeyAssignExtras {
    const val KEY = "key_code"
    const val DIRECTION = "direction"
    const val MODE = "mode"
    const val HAS_DEFAULT = "has_default"
    const val IS_CUSTOM = "is_custom"
    const val LABEL = "current_label"
    const val LABEL_KEY_FONT = "label_key_font"

    fun write(
        request: SubkeyAssignRequest,
        putString: (String, String?) -> Unit,
        putBoolean: (String, Boolean) -> Unit,
    ) {
        putString(KEY, request.keyCode)
        putString(DIRECTION, request.direction.name)
        putString(MODE, request.mode.name)
        putBoolean(HAS_DEFAULT, request.hasDefault)
        putBoolean(IS_CUSTOM, request.isCustom)
        putString(LABEL, request.currentLabel)
        putBoolean(LABEL_KEY_FONT, request.labelUsesKeyFont)
    }

    fun read(getString: (String) -> String?, getBoolean: (String, Boolean) -> Boolean): SubkeyAssignRequest? {
        val keyCode = getString(KEY)?.takeIf { it.isNotEmpty() } ?: return null
        val direction = getString(DIRECTION)
            ?.let { name -> SwipeDirection.entries.firstOrNull { it.name == name } } ?: return null
        val mode = getString(MODE)
            ?.let { name -> SubkeyAssignRequest.Mode.entries.firstOrNull { it.name == name } } ?: return null
        return SubkeyAssignRequest(
            keyCode = keyCode,
            direction = direction,
            mode = mode,
            hasDefault = getBoolean(HAS_DEFAULT, false),
            isCustom = getBoolean(IS_CUSTOM, false),
            currentLabel = getString(LABEL),
            labelUsesKeyFont = getBoolean(LABEL_KEY_FONT, false),
        )
    }
}
