package tribixbite.cleverkeys.prefs

import android.content.SharedPreferences
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * GitHub issue #169: "Next n previous layout keys cannot be removed" — unticking
 * `switch_forward` / `switch_backward` in the Extra Keys configuration did nothing while
 * every other extra key removed fine.
 *
 * Root cause: the two keys never went through the extra-keys machinery in the first place.
 * They are baked into the bottom row (`res/xml/bottom_row.xml`, space-bar corner gestures
 * key7/key8, mirrored by `src/main/layouts/latn_neo2.xml`'s embedded bottom row) WITHOUT
 * the `loc ` prefix. Every removable extra key on the bottom row is `loc` — LayoutModifier
 * strips `loc` keys unless the extra-keys map (checkbox prefs) contains them — but a
 * non-`loc` key is unconditionally kept, gated only by `layouts.size > 1` in
 * `LayoutModifier.modify_key`. So the checkbox controlled nothing the user could see.
 *
 * The fix keeps every existing default visible while honouring removal:
 *  - the bottom-row instances become `loc switch_forward` / `loc switch_backward`;
 *  - `defaultChecked` turns both ON by default (so multi-layout users keep their switch
 *    gestures with no stored prefs — value-preserving for existing installs);
 *  - `dropLayoutSwitchKeys` removes both from the computed extra-keys map when only one
 *    layout is enabled, preserving the pre-fix single-layout default (no useless no-op
 *    switch key appears just because the checkbox now defaults to on).
 */
class ExtraKeysSwitchRemovalTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        prefs = mockk()
        every { prefs.getBoolean(any(), any()) } answers { secondArg() }
    }

    @After
    fun teardown() = unmockkAll()

    // ------------------------------------------------- the layout files (root cause)

    /**
     * The bottom row must declare the layout-switch gestures as `loc` so the extra-keys
     * checkboxes govern their presence. Non-`loc`, they are unremovable (#169).
     */
    @Test
    fun bottomRow_declaresSwitchKeysAsLoc() {
        val xml = File("res/xml/bottom_row.xml").readText()
        assertWithMessage(
            "bottom_row.xml must declare key7=\"loc switch_forward\" — without `loc `, " +
            "LayoutModifier keeps the key whenever 2+ layouts are enabled regardless of the " +
            "extra_key_switch_forward checkbox (bug #169)."
        ).that(xml.contains("key7=\"loc switch_forward\"")).isTrue()
        assertWithMessage(
            "bottom_row.xml must declare key8=\"loc switch_backward\" (bug #169)."
        ).that(xml.contains("key8=\"loc switch_backward\"")).isTrue()
    }

    /**
     * latn_neo2.xml ships its own copy of the bottom row (bottom_row="false") and must
     * carry the same `loc` markers, or neo2 users keep the unremovable keys.
     */
    @Test
    fun neo2EmbeddedBottomRow_declaresSwitchKeysAsLoc() {
        val xml = File("src/main/layouts/latn_neo2.xml").readText()
        assertWithMessage("latn_neo2.xml embedded bottom row must use loc switch_forward (bug #169)")
            .that(xml.contains("key7=\"loc switch_forward\"")).isTrue()
        assertWithMessage("latn_neo2.xml embedded bottom row must use loc switch_backward (bug #169)")
            .that(xml.contains("key8=\"loc switch_backward\"")).isTrue()
    }

    // ------------------------------------------------- defaults (value preservation)

    /**
     * With the bottom-row instances now `loc`-gated, the checkboxes must default ON —
     * otherwise every existing multi-layout install with no stored `extra_key_switch_*`
     * pref would silently lose its layout-switch gestures on upgrade.
     */
    @Test
    fun switchKeys_areDefaultChecked() {
        assertWithMessage(
            "defaultChecked(\"switch_forward\") must be true: the bottom-row instance is now " +
            "loc-gated by this checkbox, and multi-layout users with no stored pref must keep it."
        ).that(ExtraKeysPreference.defaultChecked("switch_forward")).isTrue()
        assertWithMessage("defaultChecked(\"switch_backward\") must be true (same reasoning).")
            .that(ExtraKeysPreference.defaultChecked("switch_backward")).isTrue()
    }

    // ------------------------------------------------- the untick is honoured

    /** An explicit untick must keep the key out of the computed extra-keys set. */
    @Test
    fun uncheckedSwitchKey_isAbsentFromComputedExtraKeys() {
        every { prefs.getBoolean("extra_key_switch_forward", any()) } returns false
        every { prefs.getBoolean("extra_key_switch_backward", any()) } returns false

        val names = ExtraKeysPreference.getExtraKeys(prefs).keys.map { it.toString() }
        assertWithMessage("unticked switch_forward must not be in the extra-keys set")
            .that(names).doesNotContain("switch_forward")
        assertWithMessage("unticked switch_backward must not be in the extra-keys set")
            .that(names).doesNotContain("switch_backward")
    }

    // ------------------------------------------------- single-layout gating

    /**
     * `LayoutModifier.modify_layout` must drop the switch keys from the computed extra-keys
     * map when only one layout is enabled. Without this, the new default-ON checkboxes would
     * make `addExtraKeys` re-add a no-op switch key for single-layout users (whose bottom-row
     * copy `modify_key` correctly removed) — a regression the pre-fix default never had.
     *
     * Source-scan (idiom of the drift tests): modify_layout's body must invoke the guard
     * between building the extra-keys map and applying it.
     */
    @Test
    fun modifyLayout_guardsSwitchKeysForSingleLayout() {
        val src = File("src/main/kotlin/tribixbite/cleverkeys/LayoutModifier.kt").readText()
        val fnStart = src.indexOf("fun modify_layout(")
        assertWithMessage("modify_layout must exist in LayoutModifier.kt")
            .that(fnStart).isGreaterThan(-1)
        val body = src.substring(fnStart, minOf(fnStart + 3000, src.length))
        assertWithMessage(
            "modify_layout must call ExtraKeysPreference.dropLayoutSwitchKeys(extra_keys, " +
            "globalConfig.layouts.size) after populating the extra-keys map, so single-layout " +
            "keyboards never grow a no-op switch key from the default-ON checkbox (#169)."
        ).that(body.contains("ExtraKeysPreference.dropLayoutSwitchKeys(extra_keys, globalConfig.layouts.size)"))
            .isTrue()
    }

    /** Behavioural check of the guard itself. */
    @Test
    fun dropLayoutSwitchKeys_removesOnlyWhenSingleLayout() {
        val forward = tribixbite.cleverkeys.KeyValue.getKeyByName("switch_forward")
        val backward = tribixbite.cleverkeys.KeyValue.getKeyByName("switch_backward")
        val tab = tribixbite.cleverkeys.KeyValue.getKeyByName("tab")

        val single = mutableMapOf(forward to "f", backward to "b", tab to "t")
        ExtraKeysPreference.dropLayoutSwitchKeys(single, layoutCount = 1)
        assertWithMessage("with one layout the switch keys are no-ops and must be dropped")
            .that(single.keys).containsExactly(tab)

        val multi = mutableMapOf(forward to "f", backward to "b", tab to "t")
        ExtraKeysPreference.dropLayoutSwitchKeys(multi, layoutCount = 2)
        assertThat(multi.keys).containsExactly(forward, backward, tab)
    }
}
