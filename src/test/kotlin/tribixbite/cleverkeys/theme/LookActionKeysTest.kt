package tribixbite.cleverkeys.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * A look's editor-action key ([LookActionStyle], 2026-10-28): which fill it takes and whether it
 * shows an icon or the action's text, per product. Device references: the real Gboard and
 * SwiftKey on the Pixel 8 Pro, one Chrome page with an input per `enterkeyhint`, light and dark
 * (`build/oct27-night-pixel/action/`); iOS from research §1a / §1b.
 */
class LookActionKeysTest {

    private fun look(id: String) = KeyboardLooks.byId(id)!!

    /** The six IME actions, in id order (ENTER is plain Enter, not an action). */
    private val IME_KINDS = ActionKind.entries - ActionKind.ENTER

    @Test
    fun imeActionsMapToKinds() {
        // EditorInfo.IME_ACTION_GO..PREVIOUS = 2..7; 0 (unspecified, or a custom label) and 1 (none) draw text.
        assertThat(ActionKind.of(2)).isEqualTo(ActionKind.GO)
        assertThat(ActionKind.of(3)).isEqualTo(ActionKind.SEARCH)
        assertThat(ActionKind.of(4)).isEqualTo(ActionKind.SEND)
        assertThat(ActionKind.of(5)).isEqualTo(ActionKind.NEXT)
        assertThat(ActionKind.of(6)).isEqualTo(ActionKind.DONE)
        assertThat(ActionKind.of(7)).isEqualTo(ActionKind.PREVIOUS)
        assertThat(ActionKind.of(0)).isNull()
        assertThat(ActionKind.of(1)).isNull()
        assertWithMessage("plain Enter is not an IME action").that(ActionKind.of(ActionKind.ENTER.id)).isNull()
    }

    /** Plain Enter (multi-line fields) on the same three looks: a thin ↵ line icon, as on the device. */
    @Test
    fun plainEnterDrawsTheReturnIconOnLooksThatDrawIcons() {
        for (id in listOf(KeyboardLooks.GBOARD, KeyboardLooks.SWIFTKEY, KeyboardLooks.IOS)) {
            assertWithMessage(id).that(LookActionKeys.glyph(look(id).action, ActionKind.ENTER)).isEqualTo(ActionGlyph.RETURN)
        }
        assertWithMessage("iOS 18 keeps CleverKeys' glyph").that(LookActionKeys.glyph(look(KeyboardLooks.IOS18).action, ActionKind.ENTER)).isNull()
    }

    @Test
    fun gboardDrawsAnIconPerActionOnTheFunctionKeyFill() {
        val s = look(KeyboardLooks.GBOARD).action!!
        assertThat(IME_KINDS.map { LookActionKeys.glyph(s, it) }).containsExactly(
            ActionGlyph.ARROW, ActionGlyph.SEARCH, ActionGlyph.SEND, ActionGlyph.NEXT, ActionGlyph.DONE, ActionGlyph.PREVIOUS,
        ).inOrder()
        for (k in IME_KINDS) assertWithMessage("$k fill").that(LookActionKeys.usesActionFill(s, k)).isFalse()
    }

    @Test
    fun swiftKeyIconsDifferFromGboards() {
        val s = look(KeyboardLooks.SWIFTKEY).action!!
        assertThat(IME_KINDS.map { LookActionKeys.glyph(s, it) }).containsExactly(
            ActionGlyph.DONE, ActionGlyph.SEARCH, ActionGlyph.SEND_OUTLINE, ActionGlyph.ARROW, ActionGlyph.RETURN, ActionGlyph.RETURN,
        ).inOrder()
        for (k in IME_KINDS) assertThat(LookActionKeys.usesActionFill(s, k)).isFalse()
    }

    @Test
    fun iosProminentReturnIsBlueWithAnIconAndIos18UsesLowercaseText() {
        val ios = look(KeyboardLooks.IOS).action!!
        assertThat(LookActionKeys.glyph(ios, ActionKind.GO)).isEqualTo(ActionGlyph.ARROW)
        assertThat(LookActionKeys.glyph(ios, ActionKind.SEARCH)).isEqualTo(ActionGlyph.SEARCH)
        assertThat(LookActionKeys.glyph(ios, ActionKind.SEND)).isEqualTo(ActionGlyph.ARROW_UP)
        assertThat(LookActionKeys.glyph(ios, ActionKind.DONE)).isEqualTo(ActionGlyph.DONE)
        for (k in listOf(ActionKind.GO, ActionKind.SEARCH, ActionKind.SEND, ActionKind.DONE))
            assertThat(LookActionKeys.usesActionFill(ios, k)).isTrue()
        assertWithMessage("next is not a prominent return").that(LookActionKeys.usesActionFill(ios, ActionKind.NEXT)).isFalse()

        val ios18 = look(KeyboardLooks.IOS18).action!!
        for (k in IME_KINDS) assertThat(LookActionKeys.glyph(ios18, k)).isNull()
        assertThat(LookActionKeys.text(ios18, "Search", java.util.Locale.US)).isEqualTo("search")
        assertThat(LookActionKeys.usesActionFill(ios18, ActionKind.SEND)).isTrue()
        assertThat(LookActionKeys.usesActionFill(ios18, ActionKind.NEXT)).isFalse()
    }

    @Test
    fun noStyleOrNoKindKeepsTodaysTextOnTheActionFill() {
        assertThat(LookActionKeys.glyph(null, ActionKind.GO)).isNull()
        assertThat(LookActionKeys.usesActionFill(null, ActionKind.GO)).isTrue()
        val gboard = look(KeyboardLooks.GBOARD).action!!
        assertWithMessage("a custom label / unspecified action").that(LookActionKeys.glyph(gboard, null)).isNull()
        assertThat(LookActionKeys.text(null, "Go", java.util.Locale.US)).isEqualTo("Go")
        assertWithMessage("Samsung: no device reference, unchanged").that(look(KeyboardLooks.SAMSUNG).action).isNull()
    }

    @Test
    fun everyGlyphHasDrawablePartsInsideTheUnitBox() {
        for (g in ActionGlyph.entries) {
            val parts = LookActionKeys.parts(g)
            assertWithMessage("$g").that(parts).isNotEmpty()
            for (p in parts) when (p) {
                is IconPart.Path -> {
                    assertThat(p.points.size).isAtLeast(2)
                    for ((x, y) in p.points) assertWithMessage("$g ($x, $y)").that(x in 0f..1f && y in 0f..1f).isTrue()
                }
                is IconPart.Circle -> {
                    assertThat(p.cx - p.r).isAtLeast(0f); assertThat(p.cx + p.r).isAtMost(1f)
                    assertThat(p.cy - p.r).isAtLeast(0f); assertThat(p.cy + p.r).isAtMost(1f)
                }
            }
        }
        assertWithMessage("Gboard's send icon is filled, SwiftKey's outlined")
            .that(LookActionKeys.parts(ActionGlyph.SEND).any { it is IconPart.Path && it.filled }).isTrue()
        assertThat(LookActionKeys.parts(ActionGlyph.SEND_OUTLINE).none { it is IconPart.Path && it.filled }).isTrue()
    }

    @Test
    fun theStyleTravelsThroughXmlAndJson() {
        val problems = ArrayList<String>()
        val spec = ThemeSpecXml.parse(
            mapOf("action_icons" to "go:arrow search:search done:done", "action_accent" to "go search", "action_lowercase" to "true"),
            null, null, problems,
        )
        assertThat(problems).isEmpty()
        val want = LookActionStyle(
            icons = mapOf(ActionKind.GO to ActionGlyph.ARROW, ActionKind.SEARCH to ActionGlyph.SEARCH, ActionKind.DONE to ActionGlyph.DONE),
            accent = setOf(ActionKind.GO, ActionKind.SEARCH), lowercase = true,
        )
        assertThat(spec.action).isEqualTo(want)
        assertThat(ThemeSpecJson.fromJson(org.json.JSONObject(ThemeSpecJson.toJson(spec).toString())).action).isEqualTo(want)
        for (l in KeyboardLooks.ALL) {
            assertWithMessage("${l.id} saved as a custom theme keeps its action key")
                .that(ThemeSpecs.ofLook(l).action).isEqualTo(l.action)
        }
        val noAccent = ThemeSpecXml.parse(mapOf("action_accent" to ""), null, null, problems)
        assertWithMessage("an empty accent list = every action on the Enter fill").that(noAccent.action?.accent).isEmpty()
        val bad = ArrayList<String>()
        ThemeSpecXml.parse(mapOf("action_icons" to "go:rocket"), null, null, bad)
        assertThat(bad).containsExactly("<theme> action_icons=\"go:rocket\"")
    }
}
