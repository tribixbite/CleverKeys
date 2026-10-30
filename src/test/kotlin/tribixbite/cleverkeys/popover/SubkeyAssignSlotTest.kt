package tribixbite.cleverkeys.popover

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.KeySubkeySources
import tribixbite.cleverkeys.customization.ShortSwipeMapping
import tribixbite.cleverkeys.customization.SubkeyEditPolicy
import tribixbite.cleverkeys.customization.SubkeyKeyContext
import tribixbite.cleverkeys.customization.SubkeySource
import tribixbite.cleverkeys.customization.SwipeDirection

/**
 * The popover's assign screen resolves its slot like the per-key dialog's rows
 * ([subkeyAssignSlot] → [KeySubkeySources]), so [SubkeyEditPolicy] sees the same slot from both
 * screens; without a live key (keyboard not running) it falls back to what the popover reported.
 */
class SubkeyAssignSlotTest {

    private val q: KeyboardData.Key = run {
        val keys = MutableList<KeyValue?>(9) { null }
        keys[0] = KeyValue.getKeyByName("q")
        keys[SwipeDirection.NW.subLabelIndex] = KeyValue.getKeyByName("~")
        KeyboardData.Key(keys, null, 0, 1f, 0f, null)
    }

    private fun request(hasDefault: Boolean, mode: SubkeyAssignRequest.Mode = SubkeyAssignRequest.Mode.EDIT) =
        SubkeyAssignRequest("q", SwipeDirection.NW, mode, hasDefault, isCustom = false, currentLabel = "~")

    private fun context(live: KeyboardData.Key?) =
        SubkeyKeyContext(live, 1f, live, live != null, 0, null, 0, 0, "QWERTY (US)", "QWERTY (US) (modified)")

    @Test
    fun aLiveKeyResolvesLikeThePerKeyDialog() {
        val slot = subkeyAssignSlot(request(true), context(q), null)
        assertThat(slot).isEqualTo(KeySubkeySources.resolve(q, true, q, emptyMap()).first { it.direction == SwipeDirection.NW })
        assertThat(slot.source).isEqualTo(SubkeySource.LAYOUT)
        assertThat(SubkeyEditPolicy.route(slot, copyNeeded = true)).isEqualTo(SubkeyEditPolicy.Route.ASK_COPY)
    }

    @Test
    fun withoutALiveKeyTheReportedDefaultIsAnEditByCustomMapping() {
        val shown = subkeyAssignSlot(request(true), context(null), null)
        assertThat(shown.source).isEqualTo(SubkeySource.SHARED)
        assertThat(SubkeyEditPolicy.route(shown, copyNeeded = true)).isEqualTo(SubkeyEditPolicy.Route.GLOBAL)
        assertThat(SubkeyEditPolicy.globalRemoval(shown)).isEqualTo(SubkeyEditPolicy.GlobalRemoval.BLANK)

        val removed = subkeyAssignSlot(request(true, SubkeyAssignRequest.Mode.ASSIGN), context(null), ShortSwipeMapping.removal("q", SwipeDirection.NW))
        assertThat(removed.source).isEqualTo(SubkeySource.REMOVED)
        assertThat(SubkeyEditPolicy.restore(removed)).isEqualTo(SubkeyEditPolicy.Restore.RESTORE)

        val custom = ShortSwipeMapping("q", SwipeDirection.NW, "x", ActionType.TEXT, "x")
        val empty = subkeyAssignSlot(request(false), context(null), custom)
        assertThat(empty.source).isEqualTo(SubkeySource.CUSTOM)
        assertThat(SubkeyEditPolicy.restore(empty)).isEqualTo(SubkeyEditPolicy.Restore.DELETE)
        assertThat(subkeyAssignSlot(request(false), context(null), null).source).isEqualTo(SubkeySource.NONE)
    }

    /**
     * Saga 2026-10-30: the edit screen (one slot, no direction grid) showed the per-key dialog's
     * hint "Subkeys of QWERTY (US) and custom short swipes. Tap a direction to change it." — an
     * instruction for a grid this screen does not have. It names the layout with its own string.
     */
    @Test
    fun theEditScreenNamesTheLayoutWithoutTheDialogsDirectionHint() {
        val screen = File("src/main/kotlin/tribixbite/cleverkeys/popover/SubkeyAssignActivity.kt").readText()
        assertThat(screen).doesNotContain("R.string.subkey_dialog_layout_hint")
        assertThat(screen).contains("R.string.subkey_edit_layout_source")
        val english = File("res/values/strings.xml").readText()
        assertThat(english).contains("<string name=\"subkey_edit_layout_source\">A subkey of %1\$s.</string>")
    }
}
