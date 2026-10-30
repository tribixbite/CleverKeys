package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData

/**
 * [SubkeyEditPolicy] decides, for BOTH screens that edit a subkey — Settings → Short Swipe
 * Customization's per-key dialog and the subkey popover's assign/edit screen — where a change or
 * removal goes (docs/specs/subkey-customization-current.md "One policy for both screens").
 *
 * Before 2026-10-30 the popover's screen sent every edit to a global custom mapping, even of a
 * subkey the current layout's own XML defines, while the per-key dialog asked to make a modified
 * copy of the layout. This pins the one rule both now follow.
 */
class SubkeyEditPolicyTest {

    private fun kv(name: String) = KeyValue.getKeyByName(name)

    private fun key(vararg subs: Pair<SwipeDirection, String>): KeyboardData.Key {
        val keys = MutableList<KeyValue?>(9) { null }
        keys[0] = kv("q")
        for ((dir, value) in subs) keys[dir.subLabelIndex] = kv(value)
        return KeyboardData.Key(keys, null, 0, 1f, 0f, null)
    }

    /** q: NW `~` from the layout, SE `esc` defined but hidden (loc), E `f1` an Extra key. */
    private fun slots(layoutOwned: Boolean, customs: Map<SwipeDirection, ShortSwipeMapping> = emptyMap()) =
        KeySubkeySources.resolve(
            definedKey = key(SwipeDirection.NW to "~", SwipeDirection.SE to "esc"),
            layoutOwned = layoutOwned,
            liveKey = key(SwipeDirection.NW to "~", SwipeDirection.E to "f1"),
            customs = customs,
        ).associateBy { it.direction }

    private val custom = ShortSwipeMapping("q", SwipeDirection.NW, "v", ActionType.COMMAND, "voice_input")
    private val customOnEmpty = ShortSwipeMapping("q", SwipeDirection.S, "x", ActionType.TEXT, "x")

    @Test
    fun aBuiltInLayoutsOwnSubkeyAsksForACopyFirst() {
        val s = slots(layoutOwned = true)
        for (dir in listOf(SwipeDirection.NW, SwipeDirection.SE)) {
            assertWithMessage("$dir").that(SubkeyEditPolicy.route(s.getValue(dir), copyNeeded = true))
                .isEqualTo(SubkeyEditPolicy.Route.ASK_COPY)
        }
    }

    @Test
    fun aCustomLayoutsOwnSubkeyIsEditedInPlace() {
        val s = slots(layoutOwned = true)
        assertThat(SubkeyEditPolicy.route(s.getValue(SwipeDirection.NW), copyNeeded = false))
            .isEqualTo(SubkeyEditPolicy.Route.LAYOUT)
    }

    @Test
    fun everythingElseIsACustomMapping() {
        val owned = slots(layoutOwned = true, customs = mapOf(SwipeDirection.NW to custom, SwipeDirection.S to customOnEmpty))
        // A custom mapping over a layout subkey, an Extra key, an empty slot, a custom mapping on
        // an empty slot.
        for (dir in listOf(SwipeDirection.NW, SwipeDirection.E, SwipeDirection.N, SwipeDirection.S)) {
            assertWithMessage("$dir").that(SubkeyEditPolicy.route(owned.getValue(dir), copyNeeded = true))
                .isEqualTo(SubkeyEditPolicy.Route.GLOBAL)
        }
        // Not the layout's own key (the shared bottom row, a pane): never the layout.
        val shared = slots(layoutOwned = false)
        assertThat(SubkeyEditPolicy.route(shared.getValue(SwipeDirection.NW), copyNeeded = true))
            .isEqualTo(SubkeyEditPolicy.Route.GLOBAL)
    }

    @Test
    fun theCopyQuestionsAnswersChooseTheTarget() {
        assertThat(SubkeyEditPolicy.targetAfterCopyQuestion(createCopy = true)).isEqualTo(SlotEditTarget.LAYOUT)
        assertThat(SubkeyEditPolicy.targetAfterCopyQuestion(createCopy = false)).isEqualTo(SlotEditTarget.GLOBAL)
    }

    @Test
    fun aLayoutEditTheLayoutCannotHoldBecomesACustomMappingAndSaysSo() {
        assertThat(SubkeyEditPolicy.save(SlotEditTarget.LAYOUT, "voice_input")).isEqualTo(SubkeyEditPolicy.Save.LAYOUT)
        assertThat(SubkeyEditPolicy.save(SlotEditTarget.LAYOUT, null)).isEqualTo(SubkeyEditPolicy.Save.GLOBAL_INSTEAD_OF_LAYOUT)
        assertThat(SubkeyEditPolicy.save(SlotEditTarget.GLOBAL, "voice_input")).isEqualTo(SubkeyEditPolicy.Save.GLOBAL)
        assertThat(SubkeyEditPolicy.save(SlotEditTarget.GLOBAL, null)).isEqualTo(SubkeyEditPolicy.Save.GLOBAL)
    }

    @Test
    fun aGlobalRemovalBlanksWhatIsShownAndOtherwiseDeletesTheMapping() {
        val s = slots(layoutOwned = false, customs = mapOf(SwipeDirection.NW to custom, SwipeDirection.S to customOnEmpty))
        assertThat(SubkeyEditPolicy.globalRemoval(s.getValue(SwipeDirection.NW))).isEqualTo(SubkeyEditPolicy.GlobalRemoval.BLANK)
        assertThat(SubkeyEditPolicy.globalRemoval(s.getValue(SwipeDirection.E))).isEqualTo(SubkeyEditPolicy.GlobalRemoval.BLANK)
        assertThat(SubkeyEditPolicy.globalRemoval(s.getValue(SwipeDirection.S))).isEqualTo(SubkeyEditPolicy.GlobalRemoval.DELETE)
    }

    @Test
    fun restoreBringsBackWhatACustomMappingHides() {
        val s = slots(layoutOwned = true, customs = mapOf(
            SwipeDirection.NW to custom,
            SwipeDirection.S to customOnEmpty,
            SwipeDirection.SE to ShortSwipeMapping.removal("q", SwipeDirection.SE),
        ))
        assertThat(SubkeyEditPolicy.restore(s.getValue(SwipeDirection.NW))).isEqualTo(SubkeyEditPolicy.Restore.RESTORE)
        // A `removed` blank over a hidden layout subkey still restores it.
        assertThat(SubkeyEditPolicy.restore(s.getValue(SwipeDirection.SE))).isEqualTo(SubkeyEditPolicy.Restore.RESTORE)
        assertThat(SubkeyEditPolicy.restore(s.getValue(SwipeDirection.S))).isEqualTo(SubkeyEditPolicy.Restore.DELETE)
        assertThat(SubkeyEditPolicy.restore(s.getValue(SwipeDirection.N))).isNull()
    }
}
