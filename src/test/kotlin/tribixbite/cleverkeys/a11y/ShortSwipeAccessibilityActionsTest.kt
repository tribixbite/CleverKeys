package tribixbite.cleverkeys.a11y

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SwipeDirection
import tribixbite.cleverkeys.popover.SubkeyPopoverSlots

/**
 * Pure-JVM tests for [ShortSwipeAccessibilityActions]: the TalkBack custom actions that stand
 * in for per-key short swipes, which explore-by-touch consumes (maintainer report 2026-10-26:
 * "you can't access the clipboard panel when TalkBack is on").
 *
 * Slots are built with the real [SubkeyPopoverSlots.resolve] — the resolver the live keyboard
 * uses — so these tests pin what each slot kind is announced as, not a parallel model of it.
 * The string resolver is a fake: the action format and direction names resolve to their
 * English text, every other id to a `str#<id>` sentinel.
 */
class ShortSwipeAccessibilityActionsTest {

    private val english = mapOf(
        R.string.a11y_short_swipe_action to "%1\$s (%2\$s)",
        R.string.swipe_direction_north to "North",
        R.string.swipe_direction_northeast to "Northeast",
        R.string.swipe_direction_east to "East",
        R.string.swipe_direction_southeast to "Southeast",
        R.string.swipe_direction_south to "South",
        R.string.swipe_direction_southwest to "Southwest",
        R.string.swipe_direction_west to "West",
        R.string.swipe_direction_northwest to "Northwest",
    )
    private val res: (Int) -> String = { id -> english[id] ?: "str#$id" }
    private fun s(id: Int) = "str#$id"

    /** Slots of the bottom-row Ctrl key: `key3="loc switch_clipboard"` (SW), `key4="switch_numeric"` (SE). */
    private fun ctrlSlots(custom: Map<SwipeDirection, ShortSwipeMapping> = emptyMap()) =
        SubkeyPopoverSlots.resolve(
            defaultAt = { dir ->
                when (dir) {
                    SwipeDirection.SW -> KeyValue.getKeyByName("switch_clipboard")
                    SwipeDirection.SE -> KeyValue.getKeyByName("switch_numeric")
                    else -> null
                }
            },
            customAt = { dir -> custom[dir] },
        )

    @Test
    fun ctrlKeyOffersTheClipboardPaneAsAnAction() {
        val actions = ShortSwipeAccessibilityActions.actionsFor(ctrlSlots(), res)

        assertThat(actions).containsExactly(
            ShortSwipeAccessibilityActions.Action(
                SwipeDirection.SE, "${s(R.string.key_descr_switch_numeric)} (Southeast)"
            ),
            ShortSwipeAccessibilityActions.Action(
                SwipeDirection.SW, "${s(R.string.key_descr_clipboard)} (Southwest)"
            ),
        ).inOrder() // clockwise from north, the order the popover and settings use
    }

    @Test
    fun emptyAndRemovedSlotsOfferNothing() {
        val removed = ShortSwipeMapping.removal("ctrl", SwipeDirection.SW)
        val actions = ShortSwipeAccessibilityActions.actionsFor(
            ctrlSlots(mapOf(SwipeDirection.SW to removed)), res
        )
        // The removal blanks the clipboard slot exactly as it does for a real swipe (no-op).
        assertThat(actions.map { it.direction }).containsExactly(SwipeDirection.SE)
    }

    @Test
    fun customMappingReplacesTheDefaultInItsSlot() {
        val euro = ShortSwipeMapping.textInput("ctrl", SwipeDirection.SW, "€", "€")
        val actions = ShortSwipeAccessibilityActions.actionsFor(
            ctrlSlots(mapOf(SwipeDirection.SW to euro)), res
        )
        assertThat(actions).contains(ShortSwipeAccessibilityActions.Action(SwipeDirection.SW, "€ (Southwest)"))
        assertThat(actions.map { it.label }).doesNotContain("${s(R.string.key_descr_clipboard)} (Southwest)")
    }

    @Test
    fun commandMappingSpeaksItsCatalogueName() {
        val paste = ShortSwipeMapping("a", SwipeDirection.N, "P", ActionType.COMMAND, "paste")
        assertThat(ShortSwipeAccessibilityActions.describeMapping(paste, res)).isEqualTo(s(R.string.cmd_paste))
    }

    @Test
    fun commandMappingOutsideTheCatalogueFallsBackToTheKeyLabel() {
        // `switch_clipboard` is a special key name; whether or not the catalogue lists it, the
        // spoken label must be a real name, never the PUA glyph or the raw id.
        val mapping = ShortSwipeMapping("a", SwipeDirection.N, "", ActionType.COMMAND, "switch_clipboard")
        val label = ShortSwipeAccessibilityActions.describeMapping(mapping, res)
        assertThat(label).isNotEmpty()
        assertThat(label.any { it.code in 0xE000..0xF8FF }).isFalse()
        assertThat(label).isNotEqualTo("switch_clipboard")
    }

    @Test
    fun textMappingsSpeakTheirText() {
        val space = ShortSwipeMapping.textInput("a", SwipeDirection.N, "␣", " ")
        val macro = ShortSwipeMapping.textInput("a", SwipeDirection.N, "addr", "221B Baker Street")
        assertThat(ShortSwipeAccessibilityActions.describeMapping(space, res)).isEqualTo(s(R.string.key_descr_space))
        assertThat(ShortSwipeAccessibilityActions.describeMapping(macro, res)).isEqualTo("221B Baker Street")
    }

    @Test
    fun keyEventMappingSpeaksTheKeyName() {
        val del = ShortSwipeMapping.keyEvent("a", SwipeDirection.N, "⌫", KeyEvent.KEYCODE_DEL)
        assertThat(ShortSwipeAccessibilityActions.describeMapping(del, res)).isEqualTo(s(R.string.key_descr_backspace))
    }

    @Test
    fun timestampMappingSpeaksItsActionType() {
        val ts = ShortSwipeMapping.timestamp("a", SwipeDirection.N, "📅", "yyyy-MM-dd")
        assertThat(ShortSwipeAccessibilityActions.describeMapping(ts, res))
            .isEqualTo(s(R.string.command_palette_timestamp_title))
    }

    @Test
    fun actionIdsAreDistinctAndRoundTrip() {
        val ids = SwipeDirection.entries.map { ShortSwipeAccessibilityActions.actionId(it) }
        assertThat(ids.toSet()).hasSize(SwipeDirection.entries.size)
        for (dir in SwipeDirection.entries) {
            assertThat(ShortSwipeAccessibilityActions.directionOf(ShortSwipeAccessibilityActions.actionId(dir)))
                .isEqualTo(dir)
        }
        // A standard action (ACTION_CLICK = 0x10) is never mistaken for a short swipe.
        assertThat(ShortSwipeAccessibilityActions.directionOf(0x10)).isNull()
    }
}
