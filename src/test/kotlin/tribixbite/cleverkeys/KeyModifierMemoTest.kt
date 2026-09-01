package tribixbite.cleverkeys

import android.view.KeyEvent

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test

/**
 * ARC-088 — `KeyModifier.modify(KeyValue, Pointers.Modifiers)` is memoized.
 *
 * The finding (`docs/audit/2026-08-28-archive-verification.md`): `Keyboard2View.onDraw`
 * reaches `modify` through `modifyKey` once per label and once per sub-label, i.e. up to
 * 9 calls per key per frame. With a transforming modifier latched, those calls can each allocate a fresh `KeyValue`,
 * so the allocation is proportional to keys x labels x frames.
 *
 * This is the second half of the WP6-R2 render-perf finding (`5b5d91dd`). The first half
 * cached the per-key lowercased code in `Keyboard2View._keyCodeLowerCache`; these tests pin
 * the same properties for the modifier memo: **identity stability on repeat lookups** (the
 * observable proof that no recomputation happened) and **invalidation at the modmap hook**
 * (the observable proof that the memo cannot serve a stale mapping).
 *
 * Identity, not equality, is the assertion that matters here: `KeyValue` has value-semantics
 * `equals`, so an equality assertion would pass just as well against the un-memoized code and
 * prove nothing.
 */
class KeyModifierMemoTest {

    private val ctrl = KeyValue.getKeyByName("ctrl")

    /** A `Modifiers` holding exactly CTRL; unlike SHIFT, it is safe in plain JVM tests. */
    private fun ctrlMods(): Pointers.Modifiers =
        Pointers.Modifiers.ofArray(arrayOf<KeyValue?>(ctrl), 1)

    @Before
    fun reset() {
        // The memo lives on an `object`, so it outlives any single test. Start from a known
        // empty state (this is also the modmap hook, so it doubles as coverage that the
        // production invalidation path is callable).
        KeyModifier.set_modmap(null)
    }

    // ------------------------------------------------------------------ memoization

    @Test
    fun `repeat modify of a transformed letter returns the same instance`() {
        val a = KeyValue.makeCharKey('a')
        val mods = ctrlMods()

        val first = KeyModifier.modify(a, mods)
        val second = KeyModifier.modify(a, mods)

        // Sanity: the input must actually be TRANSFORMED, or identity stability would be
        // vacuous (an untransformed key is returned as `k` itself, same instance either way).
        assertWithMessage("test input must exercise a transforming modifier")
            .that(first!!.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_A)
        assertWithMessage("CTRL must produce a NEW KeyValue, not echo the input")
            .that(first).isNotSameInstanceAs(a)

        assertWithMessage(
            "ARC-088: modify() must be memoized — onDraw calls it up to 9x per key per " +
                "frame; each modifier-cache miss can rerun transformation and allocate a " +
                "fresh KeyValue."
        ).that(second).isSameInstanceAs(first)
    }

    @Test
    fun `equal-valued distinct Modifiers instances share one memo entry`() {
        val a = KeyValue.makeCharKey('a')

        // Keyboard2View reassigns `_mods` from Pointers.getModifiers() on every key up/down,
        // so a fresh Modifiers object arrives constantly. Keying on identity would thrash;
        // Modifiers has value-semantics equals/hashCode (contentEquals over its backing
        // array), so an equal-valued rebuild must hit the same entry.
        val first = KeyModifier.modify(a, ctrlMods())
        val second = KeyModifier.modify(a, ctrlMods())

        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun `memo is per key and per modifier set`() {
        val a = KeyValue.makeCharKey('a')
        val b = KeyValue.makeCharKey('b')
        val noMods = Pointers.Modifiers.EMPTY

        assertThat(KeyModifier.modify(a, ctrlMods())!!.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_A)
        assertThat(KeyModifier.modify(b, ctrlMods())!!.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_B)
        // Same key, different modifier set — must NOT be served the shifted entry.
        assertThat(KeyModifier.modify(a, noMods)!!.getString()).isEqualTo("a")
        assertThat(KeyModifier.modify(b, noMods)!!.getString()).isEqualTo("b")

        // ...and re-asking each still answers correctly after all four are resident.
        assertThat(KeyModifier.modify(a, ctrlMods())!!.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_A)
        assertThat(KeyModifier.modify(a, noMods)!!.getString()).isEqualTo("a")
    }

    @Test
    fun `a Modifiers with a null array tail still answers correctly`() {
        // Pointers.getModifiers() sizes the backing array by POINTER count and leaves a null
        // tail, while Modifiers.equals compares the whole array. So two logically identical
        // modifier sets can compare unequal. That must cost a cache MISS and never a wrong
        // answer — pinned here because a memo that confuses the two would be silently wrong.
        val a = KeyValue.makeCharKey('a')
        val padded = Pointers.Modifiers.ofArray(arrayOf<KeyValue?>(ctrl, null), 2)

        assertThat(KeyModifier.modify(a, ctrlMods())!!.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_A)
        assertThat(KeyModifier.modify(a, padded)!!.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_A)
    }

    // ------------------------------------------------------------------ invalidation

    @Test
    fun `set_modmap invalidates the memo`() {
        val a = KeyValue.makeCharKey('a')

        val before = KeyModifier.modify(a, ctrlMods())
        assertThat(KeyModifier.modify(a, ctrlMods())).isSameInstanceAs(before)

        // The modmap is global mutable state that applyShift consults FIRST, so the same
        // (key, modifiers) pair can map to a different result after a layout switch. The memo
        // must therefore be dropped at exactly this hook, or the keyboard would keep drawing
        // the previous layout's remapped keys.
        KeyModifier.set_modmap(null)

        val after = KeyModifier.modify(a, ctrlMods())
        assertWithMessage(
            "ARC-088: set_modmap must invalidate the memo — modmap changes the (key, mods) " +
                "mapping, so a surviving entry would serve the previous layout's answer."
        ).that(after).isNotSameInstanceAs(before)
        assertThat(after!!.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_A)
    }

    @Test
    fun `a modmap remap is observed after invalidation and not before`() {
        // The strongest form of the invalidation contract: not just "a new instance", but the
        // NEW mapping actually taking effect.
        val a = KeyValue.makeCharKey('a')
        val remapped = KeyValue.makeCharKey('z')

        assertThat(KeyModifier.modify(a, ctrlMods())!!.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_A)

        val mm = Modmap()
        mm.add(Modmap.M.Ctrl, a, remapped)
        KeyModifier.set_modmap(mm)

        assertWithMessage(
            "ARC-088: after set_modmap the memo must recompute, so the new CTRL remap wins."
        ).that(KeyModifier.modify(a, ctrlMods())!!.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_Z)

        KeyModifier.set_modmap(null)
        assertThat(KeyModifier.modify(a, ctrlMods())!!.getKeyevent()).isEqualTo(KeyEvent.KEYCODE_A)
    }

    // ------------------------------------------------------------------ boundedness

    @Test
    fun `memo stays bounded under many distinct inputs`() {
        val noMods = Pointers.Modifiers.EMPTY
        repeat(KeyModifier.MODIFY_MEMO_MAX_ENTRIES + 17) { index ->
            KeyModifier.modify(KeyValue.makeStringKey("memo-$index"), noMods)
        }

        assertWithMessage("ARC-088: process-lifetime modifier memo must remain bounded")
            .that(KeyModifier.memoSizeForTest())
            .isAtMost(KeyModifier.MODIFY_MEMO_MAX_ENTRIES)
    }
}
