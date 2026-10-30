package tribixbite.cleverkeys.customization

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData

/**
 * The per-key dialog's source model (maintainer request 2026-10-30): every direction shows what a
 * swipe does now and where it comes from — the layout's own XML, a shared row, Extra Keys, a
 * custom mapping, a removal — and only a slot whose effective entry is the layout's own subkey
 * edits the layout (copy-on-write).
 */
class KeySubkeySourcesTest {

    private fun kv(name: String) = KeyValue.getKeyByName(name)

    /** A key with main value [main] and the given direction → value subkeys. */
    private fun key(main: String, vararg subs: Pair<SwipeDirection, String>): KeyboardData.Key {
        val keys = MutableList<KeyValue?>(9) { null }
        keys[0] = kv(main)
        for ((dir, value) in subs) keys[dir.subLabelIndex] = kv(value)
        return KeyboardData.Key(keys, null, 0, 1f, 0f, null)
    }

    private fun custom(dir: SwipeDirection, cmd: String) =
        ShortSwipeMapping("a", dir, "x", ActionType.COMMAND, cmd)

    private fun List<SubkeySlot>.at(dir: SwipeDirection) = first { it.direction == dir }

    @Test
    fun listsAllEightDirectionsClockwiseFromNorth() {
        val slots = KeySubkeySources.resolve(key("a"), true, null, emptyMap())
        assertThat(slots.map { it.direction }).containsExactly(
            SwipeDirection.N, SwipeDirection.NE, SwipeDirection.E, SwipeDirection.SE,
            SwipeDirection.S, SwipeDirection.SW, SwipeDirection.W, SwipeDirection.NW,
        ).inOrder()
        assertThat(slots.map { it.source }.toSet()).containsExactly(SubkeySource.NONE)
    }

    @Test
    fun eachSourceIsTold() {
        val defined = key("a", SwipeDirection.NE to "1", SwipeDirection.NW to "@", SwipeDirection.SW to "voice_typing",
            SwipeDirection.SE to "2", SwipeDirection.S to "3")
        // The keyboard shows: NE as defined, NW as defined, SW switched off, E added by Extra Keys,
        // SE and S as defined (S is then hidden by a removal, SE replaced by a custom mapping).
        val live = key("a", SwipeDirection.NE to "1", SwipeDirection.NW to "@", SwipeDirection.E to "f1",
            SwipeDirection.SE to "2", SwipeDirection.S to "3")
        val customs = mapOf(
            SwipeDirection.SE to custom(SwipeDirection.SE, "voice_input"),
            SwipeDirection.S to ShortSwipeMapping.removal("a", SwipeDirection.S),
            SwipeDirection.W to custom(SwipeDirection.W, "copy"),
        )
        val slots = KeySubkeySources.resolve(defined, true, live, customs)
        assertThat(slots.at(SwipeDirection.NE).source).isEqualTo(SubkeySource.LAYOUT)
        assertThat(slots.at(SwipeDirection.SW).source).isEqualTo(SubkeySource.HIDDEN)
        assertThat(slots.at(SwipeDirection.E).source).isEqualTo(SubkeySource.EXTRA_KEY)
        assertThat(slots.at(SwipeDirection.SE).source).isEqualTo(SubkeySource.CUSTOM)
        assertThat(slots.at(SwipeDirection.S).source).isEqualTo(SubkeySource.REMOVED)
        assertThat(slots.at(SwipeDirection.W).source).isEqualTo(SubkeySource.CUSTOM)
        assertThat(slots.at(SwipeDirection.N).source).isEqualTo(SubkeySource.NONE)
        // What a custom mapping or a removal hides is reported; a custom on an empty slot hides nothing.
        assertThat(slots.at(SwipeDirection.SE).shadowedValue).isEqualTo(kv("2"))
        assertThat(slots.at(SwipeDirection.S).shadowedValue).isEqualTo(kv("3"))
        assertThat(slots.at(SwipeDirection.W).shadowedValue).isNull()
    }

    @Test
    fun onlyTheLayoutsOwnSubkeysEditTheLayout() {
        val defined = key("a", SwipeDirection.NE to "1", SwipeDirection.SW to "voice_typing", SwipeDirection.SE to "2")
        val live = key("a", SwipeDirection.NE to "1", SwipeDirection.E to "f1", SwipeDirection.SE to "2")
        val slots = KeySubkeySources.resolve(defined, true, live,
            mapOf(SwipeDirection.SE to custom(SwipeDirection.SE, "copy")))
        assertThat(slots.at(SwipeDirection.NE).editsLayout).isTrue()
        assertThat(slots.at(SwipeDirection.SW).editsLayout).isTrue() // hidden, but the layout's own
        assertThat(slots.at(SwipeDirection.E).editsLayout).isFalse() // Extra Keys
        assertThat(slots.at(SwipeDirection.SE).editsLayout).isFalse() // the custom mapping is what is edited
        assertThat(slots.at(SwipeDirection.N).editsLayout).isFalse() // empty: a new custom mapping
    }

    @Test
    fun aSharedRowKeyIsBuiltInAndNeverEditsALayout() {
        val bottomEnter = key("enter", SwipeDirection.NW to "voice_typing", SwipeDirection.NE to "action")
        val slots = KeySubkeySources.resolve(bottomEnter, false, bottomEnter, emptyMap())
        assertThat(slots.at(SwipeDirection.NE).source).isEqualTo(SubkeySource.SHARED)
        assertThat(slots.none { it.editsLayout }).isTrue()
    }

    @Test
    fun locateFindsTheKeyByItsMainValue() {
        val layout = listOf(listOf(key("q"), key("w")), listOf(key("a"), key("s")))
        val kd = layoutOf(layout)
        assertThat(KeySubkeySources.locate(kd, kv("s"))).isEqualTo(1 to 1)
        assertThat(KeySubkeySources.locate(kd, kv("z"))).isNull()
        assertThat(KeySubkeySources.locate(kd, null)).isNull()
    }

    @Test
    fun withSubkeyShowsALayoutEditAtOnce() {
        val live = key("a", SwipeDirection.NE to "1")
        val edited = KeySubkeySources.withSubkey(live, SwipeDirection.NE, kv("voice_input"))
        assertThat(edited.keys[SwipeDirection.NE.subLabelIndex]).isEqualTo(kv("voice_input"))
        assertThat(KeySubkeySources.withSubkey(edited, SwipeDirection.NE, null).keys[SwipeDirection.NE.subLabelIndex]).isNull()
    }

    /** A KeyboardData from rows: the private constructor through reflection (no XML parser needed). */
    private fun layoutOf(rows: List<List<KeyboardData.Key>>): KeyboardData {
        val built = rows.map { KeyboardData.Row(it, 1f, 0f) }
        val ctor = KeyboardData::class.java.declaredConstructors
            .filter { c -> c.parameterTypes.none { it.name.endsWith("DefaultConstructorMarker") } }
            .maxByOrNull { it.parameterCount }!!
        ctor.isAccessible = true
        val args = ctor.parameterTypes.mapIndexed { i, t ->
            when {
                i == 0 -> built
                t == Float::class.javaPrimitiveType -> 1f
                t == Boolean::class.javaPrimitiveType -> false
                t == Int::class.javaPrimitiveType -> 0
                List::class.java.isAssignableFrom(t) -> emptyList<Any>()
                Map::class.java.isAssignableFrom(t) -> emptyMap<Any, Any>()
                else -> null
            }
        }
        return ctor.newInstance(*args.toTypedArray()) as KeyboardData
    }
}
