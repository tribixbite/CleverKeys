package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import tribixbite.cleverkeys.prefs.ExtraKeysPreference

/**
 * GitHub issue #77: "Cannot completely disable Greek/Math toggle on Numeric Layer
 * (Custom XML)".
 *
 * ## Root cause (same class as #169's unremovable layout-switch keys)
 *
 * The v1.2.8 release note promised "Greek/Math disabled in the numeric layer unless
 * enabled in extra keys (#77)", but only the Fn rewrite was gated
 * (`KeyModifier.applyFnEvent`, pinned by `ReleaseClaimKeyModifierTest`). The toggle the
 * reporter sees is a DIFFERENT injection: `src/main/layouts/numeric.xml` bakes
 * `key0="switch_greekmath"` into the ?123 pane WITHOUT the `loc ` prefix, and
 * `LayoutModifier.modify_numpad` — unlike `modify_layout` — never stripped `loc` keys at
 * all. So the key was unconditionally present on the numeric layer: the user's custom
 * layout XML cannot touch it (the pane is a shipped layout), `locale_extra_keys="false"`
 * is irrelevant to it, and the Extra Keys checkbox governed nothing there.
 *
 * ## The fix (the #169 `loc`-prefix pattern, `e7dda022`)
 *
 *  - `numeric.xml` declares the toggle as `loc switch_greekmath`;
 *  - `modify_numpad` strips a `loc` key that has an Extra Keys CHECKBOX and is not in the
 *    computed extra-keys maps ([LayoutModifier.paneLocKeyStripped]). Ungoverned `loc` keys
 *    (no Extra Keys checkbox — none ship on panes today) keep the legacy always-shown
 *    pane behaviour, because stripping them would leave no way to re-enable.
 *
 * Unlike #169, `defaultChecked("switch_greekmath")` must stay FALSE: flipping it on would
 * make `modify_layout`'s `addExtraKeys` place a Greek/Math key onto every TEXT layout that
 * lacks one (including the reporter's `bottom_row="false"` custom board) and would surface
 * the bottom row's `loc switch_greekmath` ctrl corner for every default install. Default-
 * hidden IS the published v1.2.8 behaviour; users who want the toggle tick the checkbox.
 */
class NumericPaneGreekmathRemovalTest {

    // ------------------------------------------------- the layout file (root cause)

    /** The pane's toggle must be `loc` so the extra-keys checkbox governs it (#77). */
    @Test
    fun numericPane_declaresGreekmathAsLoc() {
        val xml = File("src/main/layouts/numeric.xml").readText()
        assertWithMessage(
            "numeric.xml must declare key0=\"loc switch_greekmath\" — without `loc `, " +
                "LayoutModifier.modify_numpad keeps the Greek/Math toggle unconditionally " +
                "and the Extra Keys checkbox controls nothing the user can see (bug #77)."
        ).that(xml.contains("key0=\"loc switch_greekmath\"")).isTrue()
    }

    // ------------------------------------------------- the modifier honours `loc` on panes

    /**
     * Source-scan (drift-test idiom): `modify_numpad`'s mapper must consult the pane
     * `loc`-strip predicate before the kind dispatch — the Char branch returns early
     * ("Don't fallback into modify_key"), so a check placed after it would never see
     * localized char keys.
     */
    @Test
    fun modifyNumpad_consultsThePaneLocStrip() {
        val src = File("src/main/kotlin/tribixbite/cleverkeys/LayoutModifier.kt").readText()
        val fnStart = src.indexOf("fun modify_numpad(")
        assertWithMessage("modify_numpad must exist in LayoutModifier.kt")
            .that(fnStart).isGreaterThan(-1)
        val body = src.substring(fnStart, minOf(fnStart + 2000, src.length))
        assertWithMessage(
            "modify_numpad must strip checkbox-governed `loc` keys via paneLocKeyStripped " +
                "so the numeric/greekmath panes honour the Extra Keys checkboxes (#77)."
        ).that(body.contains("paneLocKeyStripped(")).isTrue()
    }

    // ------------------------------------------------- the predicate itself

    private val greekmath = KeyValue.getKeyByName("switch_greekmath")
    private val none = emptyMap<KeyValue, Any>()

    /** The reporter's exact state: checkbox unticked (absent from both maps) → stripped. */
    @Test
    fun locGreekmath_isStrippedWhenNotEnabled() {
        assertWithMessage("loc switch_greekmath with the checkbox off must leave the pane")
            .that(LayoutModifier.paneLocKeyStripped(greekmath, true, none, none)).isTrue()
    }

    /** Ticking the checkbox (either map) keeps the pane toggle. */
    @Test
    fun locGreekmath_isKeptWhenEnabled() {
        val enabled = mapOf(greekmath to KeyboardData.PreferredPos.DEFAULT)
        assertWithMessage("enabled via extra_keys_param must keep the pane toggle")
            .that(LayoutModifier.paneLocKeyStripped(greekmath, true, enabled, none)).isFalse()
        assertWithMessage("enabled via extra_keys_custom must keep the pane toggle")
            .that(LayoutModifier.paneLocKeyStripped(greekmath, true, none, enabled)).isFalse()
    }

    /** Non-`loc` pane keys are never the predicate's business. */
    @Test
    fun nonLocKeys_areNeverStripped() {
        assertWithMessage("a non-loc switch_greekmath (none ship) would be kept — loc opts in")
            .that(LayoutModifier.paneLocKeyStripped(greekmath, false, none, none)).isFalse()
        val digit = KeyValue.makeCharKey('7')
        assertWithMessage("plain pane keys pass through")
            .that(LayoutModifier.paneLocKeyStripped(digit, false, none, none)).isFalse()
    }

    /**
     * UNGOVERNED `loc` pane keys keep the legacy always-shown behaviour: no checkbox
     * exists for "ctrl", so a `loc ctrl` pane key could never be re-enabled once
     * stripped. Only checkbox-governed keys answer to the checkboxes. (Every `loc` key
     * the shipped panes carry today — esc/tab/alt/compose on numeric, capslock/esc/tab/
     * compose on greekmath — IS governed; this pins the safety valve for future panes.)
     */
    @Test
    fun ungovernedLocKeys_keepLegacyPaneBehaviour() {
        val ctrl = KeyValue.getKeyByName("ctrl")
        assertWithMessage("\"ctrl\" must not have an Extra Keys checkbox (premise of this pin)")
            .that(ExtraKeysPreference.EXTRA_KEYS.contains("ctrl")).isFalse()
        assertWithMessage("a loc ctrl pane key (no checkbox) would stay — loc strip is opt-in")
            .that(LayoutModifier.paneLocKeyStripped(ctrl, true, none, none)).isFalse()
    }

    /**
     * Governed keys the panes also carry (`loc esc`/`loc tab`/`loc alt` on numeric,
     * `loc capslock` on greekmath): the checkbox now governs them there too — enabled
     * keeps, absent strips. esc/tab/compose default ON, so default installs see no
     * change there; alt and capslock default OFF and now leave the panes unless enabled,
     * matching how text layouts have always treated `loc alt`/`loc capslock` (delta
     * recorded in the tracker row).
     */
    @Test
    fun otherGovernedLocPaneKeys_followTheirCheckbox() {
        for (name in listOf("esc", "tab", "alt", "capslock")) {
            val kv = KeyValue.getKeyByName(name)
            val enabled = mapOf(kv to KeyboardData.PreferredPos.DEFAULT)
            assertWithMessage("enabled '$name' must be kept on the pane")
                .that(LayoutModifier.paneLocKeyStripped(kv, true, enabled, none)).isFalse()
            assertWithMessage("disabled '$name' must be stripped from the pane")
                .that(LayoutModifier.paneLocKeyStripped(kv, true, none, none)).isTrue()
        }
    }

    // ------------------------------------------------- default stays hidden (published claim)

    /**
     * The v1.2.8 release note IS the default: "Greek/Math disabled in the numeric layer
     * unless enabled in extra keys". Flipping this default to true would not be value-
     * preserving — it would add a Greek/Math key to text layouts via `addExtraKeys`
     * (worst on `bottom_row="false"` custom boards, the reporter's exact setup).
     */
    @Test
    fun greekmathCheckbox_staysDefaultOff() {
        assertWithMessage(
            "defaultChecked(\"switch_greekmath\") must stay false — default-ON would make " +
                "addExtraKeys inject Greek/Math onto text layouts that lack it (see class KDoc)."
        ).that(ExtraKeysPreference.defaultChecked("switch_greekmath")).isFalse()
    }
}
